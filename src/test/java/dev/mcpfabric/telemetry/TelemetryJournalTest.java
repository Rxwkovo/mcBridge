package dev.mcpfabric.telemetry;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryJournalTest {
	@Test void recoversEventsWithoutOverwriting(@TempDir Path root) throws Exception {
		TelemetryJournal first = new TelemetryJournal(root, null);
		awaitReady(first);
		JsonObject e = new JsonObject(); e.addProperty("type", "sample"); e.addProperty("sequence", 1);
		assertTrue(first.append(e)); first.close();
		assertEquals(1, first.queuedCount());
		assertEquals(1, first.persistedCount());
		assertEquals(1, first.durableCount());
		TelemetryJournal second = new TelemetryJournal(root, first);
		awaitReady(second);
		assertEquals(1, second.recovered().size());
		assertEquals(1, second.recovered().get(0).get("sequence").getAsInt());
		second.close();
	}
	@Test void flushesAllQueuedRecordsBeforeClose(@TempDir Path root) throws Exception {
		TelemetryJournal journal = new TelemetryJournal(root, null); awaitReady(journal);
		for (int i = 0; i < 100; i++) {
			JsonObject record = new JsonObject(); record.addProperty("type", "sample"); record.addProperty("sequence", i + 1);
			assertTrue(journal.append(record));
		}
		journal.close();
		assertNull(journal.failure());
		assertEquals(100, journal.queuedCount());
		assertEquals(100, journal.persistedCount());
		assertEquals(100, journal.durableCount());
		TelemetryJournal reopened = new TelemetryJournal(root, journal); awaitReady(reopened);
		assertEquals(100, reopened.recovered().size()); reopened.close();
	}
	@Test void quarantinesIncompleteTailAndRetainsCompleteRecords(@TempDir Path root) throws Exception {
		Path data = Files.createDirectories(root.resolve("data"));
		Path file = data.resolve("mechagent-telemetry.jsonl");
		Files.writeString(file, "{\"type\":\"sample\",\"sequence\":1}\n{\"sequence\":");
		TelemetryJournal journal = new TelemetryJournal(root, null); awaitReady(journal);
		assertEquals(1, journal.recovered().size());
		assertTrue(journal.quarantinedTailBytes() > 0);
		try (var files = Files.list(data)) {
			assertEquals(1, files.filter(p -> p.getFileName().toString().endsWith(".corrupt")).count());
		}
		journal.close();
	}
	@Test void refusesMalformedCompleteRecordWithoutDiscardingIt(@TempDir Path root) throws Exception {
		Path data = Files.createDirectories(root.resolve("data"));
		Path file = data.resolve("mechagent-telemetry.jsonl");
		String contents = "{bad json}\n"; Files.writeString(file, contents);
		TelemetryJournal journal = new TelemetryJournal(root, null);
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (journal.failure() == null && System.nanoTime() < deadline) Thread.sleep(10);
		assertNotNull(journal.failure()); assertFalse(journal.ready());
		assertEquals(contents, Files.readString(file)); journal.close();
	}
	@Test void rotatesAndRecoversAcrossSegments(@TempDir Path root) throws Exception {
		TelemetryJournal first = new TelemetryJournal(root, null, 128, 8192); awaitReady(first);
		for (int i = 1; i <= 20; i++) {
			JsonObject record = new JsonObject(); record.addProperty("type", "sample");
			record.addProperty("sequence", i); record.addProperty("payload", "abcdefghijklmnopqrstuvwxyz");
			assertTrue(first.append(record));
		}
		first.close(); assertNull(first.failure()); assertEquals(20, first.durableCount());
		try (var files = Files.list(root.resolve("data"))) {
			assertTrue(files.filter(p -> p.toString().endsWith(".jsonl")).count() > 1);
		}
		TelemetryJournal second = new TelemetryJournal(root, first, 128, 8192); awaitReady(second);
		assertEquals(20, second.recovered().size()); assertEquals(20, second.recoveredLastSequence());
		JsonObject next = new JsonObject(); next.addProperty("sequence", 21); assertTrue(second.append(next));
		second.close(); assertNull(second.failure());
		TelemetryJournal third = new TelemetryJournal(root, second, 128, 8192); awaitReady(third);
		assertEquals(21, third.recovered().size()); assertEquals(21, third.recoveredLastSequence()); third.close();
	}
	@Test void boundsMonitorReplayWhilePreservingPauseAndSequence(@TempDir Path root) throws Exception {
		TelemetryJournal first = new TelemetryJournal(root, null, 512, 32768); awaitReady(first);
		JsonObject define = new JsonObject(); define.addProperty("type", "define"); define.addProperty("monitorId", "m"); define.addProperty("sequence", 1);
		assertTrue(first.append(define));
		JsonObject pause = new JsonObject(); pause.addProperty("type", "pause"); pause.addProperty("monitorId", "m"); pause.addProperty("sequence", 2); pause.addProperty("paused", true);
		assertTrue(first.append(pause));
		for (int i = 3; i <= 102; i++) {
			JsonObject record = new JsonObject(); record.addProperty("type", "sample"); record.addProperty("monitorId", "m"); record.addProperty("sequence", i);
			assertTrue(first.append(record));
		}
		first.close(); assertNull(first.failure());
		TelemetryJournal second = new TelemetryJournal(root, first, 512, 32768); awaitReady(second);
		assertEquals(66, second.recovered().size()); assertTrue(second.recoveredHistoryTruncated("m"));
		assertEquals("define", second.recovered().get(0).get("type").getAsString());
		assertEquals("pause", second.recovered().get(1).get("type").getAsString());
		assertTrue(second.recovered().get(1).get("paused").getAsBoolean());
		assertEquals(102, second.recoveredLastSequence()); second.close();
	}
	@Test void quotaFailsClosedAndReportsLostQueuedRecords(@TempDir Path root) throws Exception {
		TelemetryJournal journal = new TelemetryJournal(root, null, 128, 256); awaitReady(journal);
		for (int i = 0; i < 20; i++) {
			JsonObject record = new JsonObject(); record.addProperty("sequence", i + 1);
			record.addProperty("payload", "abcdefghijklmnopqrstuvwxyz0123456789");
			if (!journal.append(record)) break;
		}
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (journal.failure() == null && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals("disk_limit", journal.failure()); assertTrue(journal.dropped() > 0);
		assertTrue(journal.durableCount() <= journal.persistedCount()); journal.close();
	}
	@Test void meterReplayKeepsLatestCumulativeStateAcrossSegments(@TempDir Path root) throws Exception {
		TelemetryJournal first = new TelemetryJournal(root, null, 512, 32768); awaitReady(first);
		for (int i=0;i<25;i++) { JsonObject state=new JsonObject(); state.addProperty("type","meter_state"); state.addProperty("meterId","one"); state.addProperty("sequence",i+1); state.addProperty("count",i); assertTrue(first.append(state)); }
		first.close(); assertNull(first.failure());
		TelemetryJournal second=new TelemetryJournal(root,first,512,32768); awaitReady(second);
		assertEquals(1,second.recovered().size()); assertEquals(24,second.recovered().get(0).get("count").getAsInt()); assertEquals(25,second.recoveredLastSequence()); second.close();
	}
	private static void awaitReady(TelemetryJournal journal) throws Exception {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (!journal.ready() && journal.failure() == null && System.nanoTime() < deadline) Thread.sleep(10);
		assertNull(journal.failure()); assertTrue(journal.ready());
	}
}

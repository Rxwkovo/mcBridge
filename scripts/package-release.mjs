import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";

const read = file => readFileSync(file, "utf8");
const pkg = JSON.parse(read("mcp-server/package.json"));
const lock = JSON.parse(read("mcp-server/package-lock.json"));
const version = /^mod_version=(\d+\.\d+\.\d+)\r?$/m.exec(read("gradle.properties"))?.[1];
assert.ok(version, "Expected a stable release version");
assert.equal(pkg.version, version);
assert.equal(lock.version, version);
assert.equal(lock.packages[""].version, version);
assert.equal(pkg.private, true);
const tag = process.argv[2];
if (tag !== undefined) assert.equal(tag, "v" + version);

const out = resolve("release-assets");
mkdirSync(out);
const stage = mkdtempSync(join(tmpdir(), "mechagent-release-"));
const jar = resolve("versions/1.21.1-neoforge/build/libs/mechagent-bridge-neoforge-" + version + ".jar");
const zip = join(out, "mechagent-bridge-mcp-server-" + version + ".zip");

try {
  const entries = execFileSync("jar", ["tf", jar], { encoding: "utf8" }).trim().split(/\r?\n/);
  assert.ok(entries.includes("META-INF/neoforge.mods.toml"));
  assert.ok(entries.includes("LICENSE"));
  assert.ok(!entries.some(name => /AcceptanceFixture|(^|\/)(?:integration-test|test|saves)(?:\/|$)|(?:\.log|\.mca|\.nbt|\.jsonl)$|mcpfabric\.config\.json/i.test(name)));
  execFileSync("jar", ["xf", jar, "META-INF/neoforge.mods.toml", "LICENSE"], { cwd: stage });
  const metadata = read(join(stage, "META-INF/neoforge.mods.toml"));
  assert.ok(metadata.includes('version = "' + version + '"'), "JAR version differs from the release");
  assert.match(metadata, /modId = "neoforge"\s+type = "required"\s+versionRange = "\[21\.1\.248\]"/);
  assert.match(metadata, /modId = "minecraft"\s+type = "required"\s+versionRange = "\[1\.21\.1\]"/);
  assert.deepEqual(readFileSync(join(stage, "LICENSE")), readFileSync("LICENSE"));
  cpSync(jar, join(out, basename(jar)));
  cpSync("mcp-server/dist", join(stage, "dist"), { recursive: true });
  for (const file of ["package.json", "package-lock.json"]) cpSync("mcp-server/" + file, join(stage, file));
  cpSync("README.md", join(stage, "README.md"));
  execFileSync("jar", ["--create", "--file", zip, "--no-manifest", "--date=2026-01-01T00:00:00Z",
    "dist", "package.json", "package-lock.json", "README.md", "LICENSE"], { cwd: stage });
  const checksums = readdirSync(out).sort().map(name => {
    const hash = createHash("sha256").update(readFileSync(join(out, name))).digest("hex");
    return hash + "  " + name;
  });
  writeFileSync(join(out, "SHA256SUMS.txt"), checksums.join("\n") + "\n", { flag: "wx" });
  console.log(checksums.join("\n"));
} finally {
  rmSync(stage, { recursive: true, force: true });
}

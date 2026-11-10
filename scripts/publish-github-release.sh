#!/usr/bin/env bash
set -euo pipefail

[[ "$GITHUB_REPOSITORY" == Rxwkovo/mcBridge ]]
[[ "$GITHUB_REF" == refs/tags/v* ]]
tag="$GITHUB_REF_NAME"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
version="$(sed -n 's/^mod_version=\([0-9][0-9.]*\)$/\1/p' gradle.properties)"
[[ "$tag" == "v$version" ]]
[[ "$(git cat-file -t "refs/tags/$tag")" == tag ]]
commit="$(git rev-parse "refs/tags/$tag^{commit}")"
[[ "$(git rev-parse HEAD)" == "$commit" ]]
git merge-base --is-ancestor "$commit" refs/remotes/origin/main

jar="mechagent-bridge-neoforge-$version.jar"
server="mechagent-bridge-mcp-server-$version.zip"
checksums=SHA256SUMS.txt
expected="$(git for-each-ref --format='%(contents)' "refs/tags/$tag" | sed -n 's/^JAR SHA-256: \([0-9a-f]\{64\}\)$/\1/p')"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]]
(
  cd release-assets
  [[ "$(wc -l < "$checksums")" == 2 ]]
  [[ "$(awk '{print $2}' "$checksums" | sort)" == "$(printf '%s\n' "$jar" "$server" | sort)" ]]
  sha256sum -c "$checksums"
  [[ "$(sha256sum "$jar" | cut -d' ' -f1)" == "$expected" ]]
)

if ! gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null; then
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" --verify-tag --draft \
    --title "MechAgent Bridge $tag" --notes-file "docs/releases/$tag.md"
fi

draft="$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json isDraft --jq .isDraft)"
asset_names() {
  gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json assets --jq '.assets[].name'
}
while IFS= read -r name; do
  case "$name" in ""|"$jar"|"$server"|"$checksums") ;; *) echo "Unexpected release asset: $name" >&2; exit 1 ;; esac
done < <(asset_names)
if [[ "$draft" == true ]]; then
  gh release upload "$tag" --repo "$GITHUB_REPOSITORY" --clobber \
    "release-assets/$jar" "release-assets/$server" "release-assets/$checksums"
fi

[[ "$(asset_names | sort)" == "$(printf '%s\n' "$jar" "$server" "$checksums" | sort)" ]]
download="$(mktemp -d)"
trap 'rm -rf "$download"' EXIT
gh release download "$tag" --repo "$GITHUB_REPOSITORY" --dir "$download" \
  --pattern "$jar" --pattern "$server" --pattern "$checksums"
for file in "$jar" "$server" "$checksums"; do
  cmp "release-assets/$file" "$download/$file"
done
if [[ "$draft" == true ]]; then
  gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --draft=false --latest
fi

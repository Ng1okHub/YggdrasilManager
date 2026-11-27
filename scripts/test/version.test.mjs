/** Protects release ordering, Android update counters and tag identity. */
import assert from "node:assert/strict";
import { test } from "node:test";
import { checkTag, compareVersions, nextVersion, parseVersion, readVersion } from "../version.mjs";

test("reads the authoritative version and rejects ambiguous properties", () => {
  assert.deepEqual(readVersion("# Manager\r\nversionName=1.2.3-rc.1\r\nversionCode=42\r\n"), { name: "1.2.3-rc.1", code: 42 });
  for (const text of [
    "versionName=1.0.0\nversionCode=0", "versionName=1.0.0\nversionCode=2100000001",
    "versionName=1.0.0\nversionCode=01", "versionName=1.0.0\nversionCode=1.5",
    "versionName=1.0.0", "versionCode=1", "versionName=1.0.0\nversionName=2.0.0\nversionCode=1",
    "versionName=1.0.0\nversionCode=1\nother=x",
  ]) assert.throws(() => readVersion(text));
});

test("accepts SemVer prereleases and rejects tags and build metadata as version names", () => {
  for (const name of ["0.1.0", "1.0.0", "1.0.0-rc.1", "1.0.0-0.a-1", "1.0.0-01a"]) assert.doesNotThrow(() => parseVersion(name));
  for (const name of ["v1.0.0", "1.0", "01.0.0", "1.0.0-01", "1.0.0+build", "1.0.0-", "1.0.0-rc..1", "1.0.0\n"]) assert.throws(() => parseVersion(name));
});

test("orders releases according to SemVer including numeric prerelease identifiers", () => {
  const ordered = ["1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0"];
  for (let i = 0; i < ordered.length; i++) {
    assert.equal(compareVersions(ordered[i], ordered[i]), 0);
    for (let j = i + 1; j < ordered.length; j++) {
      assert.equal(compareVersions(ordered[i], ordered[j]), -1);
      assert.equal(compareVersions(ordered[j], ordered[i]), 1);
    }
  }
});

test("every version advance increments the Android counter exactly once", () => {
  const current = { name: "1.2.3", code: 42 };
  for (const [target, name] of [["major", "2.0.0"], ["minor", "1.3.0"], ["patch", "1.2.4"], ["1.3.0-rc.1", "1.3.0-rc.1"]]) {
    assert.deepEqual(nextVersion(current, target), { name, code: 43 });
  }
  assert.deepEqual(nextVersion({ name: "1.3.0-rc.1", code: 43 }, "1.3.0"), { name: "1.3.0", code: 44 });
  for (const target of ["1.2.3", "1.2.2", "1.2.3-rc.1"]) assert.throws(() => nextVersion(current, target));
  assert.throws(() => nextVersion({ name: "1.0.0", code: 2100000000 }, "patch"));
});

test("only the exact version tag can release the current source", () => {
  const current = { name: "1.2.3-rc.1", code: 42 };
  assert.doesNotThrow(() => checkTag(current, "v1.2.3-rc.1"));
  for (const tag of ["1.2.3-rc.1", "v1.2.3", "v1.2.3-rc.2", "v1.0.0"]) assert.throws(() => checkTag(current, tag));
});

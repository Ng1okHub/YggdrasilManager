/** Reads, advances and validates the Manager version in version.properties. */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const versionFile = resolve(dirname(fileURLToPath(import.meta.url)), "../version.properties");
const identifier = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
const semver = new RegExp(`^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(${identifier}(?:\\.${identifier})*))?$`);

/** Returns validated SemVer components for a version without build metadata. */
export function parseVersion(name) {
  const match = semver.exec(name);
  if (!match || match[0] !== name) throw new Error(`Invalid version: ${name}; expected SemVer, e.g. 1.0.0 or 1.1.0-rc.1.`);
  return { core: match.slice(1, 4).map(BigInt), pre: match[4]?.split(".") ?? [] };
}

/** Compares two versions using SemVer precedence; returns -1, 0 or 1. */
export function compareVersions(left, right) {
  const a = parseVersion(left);
  const b = parseVersion(right);
  const compare = (x, y) => x < y ? -1 : x > y ? 1 : 0;
  for (let i = 0; i < 3; i++) {
    const result = compare(a.core[i], b.core[i]);
    if (result) return result;
  }
  if (!a.pre.length) return b.pre.length ? 1 : 0;
  if (!b.pre.length) return -1;
  for (let i = 0; i < Math.max(a.pre.length, b.pre.length); i++) {
    if (a.pre[i] === undefined) return -1;
    if (b.pre[i] === undefined) return 1;
    const numericA = /^\d+$/.test(a.pre[i]);
    const numericB = /^\d+$/.test(b.pre[i]);
    const result = numericA && numericB ? compare(BigInt(a.pre[i]), BigInt(b.pre[i]))
      : numericA !== numericB ? (numericA ? -1 : 1) : compare(a.pre[i], b.pre[i]);
    if (result) return result;
  }
  return 0;
}

/** Parses the two required properties, rejecting duplicates, unknown fields and invalid Android codes. */
export function readVersion(text = readFileSync(versionFile, "utf8")) {
  const values = new Map();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^(versionName|versionCode)=(\S+)$/.exec(line);
    if (!match || values.has(match[1])) throw new Error(`Invalid or duplicate version property: ${line}`);
    values.set(match[1], match[2]);
  }
  const name = values.get("versionName") ?? "";
  const code = values.get("versionCode") ?? "";
  parseVersion(name);
  if (!/^[1-9]\d*$/.test(code) || BigInt(code) > 2100000000n) throw new Error("versionCode must be an integer from 1 to 2100000000.");
  return { name, code: Number(code) };
}

/** Advances SemVer and the Android update counter, rejecting downgrades and exhausted counters. */
export function nextVersion(current, target) {
  const parsed = parseVersion(current.name);
  let name = target;
  const index = ["major", "minor", "patch"].indexOf(target);
  if (index !== -1) {
    const core = [...parsed.core];
    core[index] += 1n;
    for (let i = index + 1; i < 3; i++) core[i] = 0n;
    name = core.join(".");
  }
  if (compareVersions(name, current.name) <= 0) throw new Error("The new version must be greater than the current version.");
  if (current.code >= 2100000000) throw new Error("Android versionCode is exhausted.");
  return { name, code: current.code + 1 };
}

/** Checks that a release tag exactly identifies this Manager version. */
export function checkTag(current, tag) {
  if (tag !== `v${current.name}`) throw new Error(`Tag ${tag} does not match version.properties; expected v${current.name}.`);
}

function main(args) {
  const current = readVersion();
  if (args.length === 0 || (args[0] === "show" && args.length === 1)) {
    console.log(JSON.stringify(current));
  } else if (args[0] === "check-tag" && args.length === 2) {
    checkTag(current, args[1]);
    console.log(`Validated ${args[1]} (versionCode ${current.code}).`);
  } else if (["bump", "set"].includes(args[0]) && args.length === 2) {
    if (args[0] === "bump" && !["major", "minor", "patch"].includes(args[1])) throw new Error("bump accepts major, minor or patch.");
    if (args[0] === "set") parseVersion(args[1]);
    const next = nextVersion(current, args[1]);
    writeFileSync(versionFile, `# Manager version, independent of the Yggdrasil kernel module version.\nversionName=${next.name}\nversionCode=${next.code}\n`);
    console.log(`${current.name} (${current.code}) -> ${next.name} (${next.code})`);
  } else {
    throw new Error("Usage: node scripts/version.mjs [show | bump major|minor|patch | set VERSION | check-tag vVERSION]");
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}

// Idempotent GitHub publisher. Token is read from GITHUB_TOKEN / GH_TOKEN only:
// never written to the repository, never printed, and redacted from git output.
//
// Usage (PowerShell):  $env:GITHUB_TOKEN="..."; node scripts/publish-github.mjs
// Usage (bash):        GITHUB_TOKEN=... node scripts/publish-github.mjs
// Classic PAT needs `repo`; fine-grained PAT needs Administration+Contents write.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const API = "https://api.github.com";
const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
const owner = process.env.GITHUB_OWNER || "andy8401";
const repo = process.env.GITHUB_REPO || "mygo-ximalaya-web-plugin";
const isPrivate = process.env.GITHUB_VISIBILITY === "private";

if (!token) {
  console.error("Missing GITHUB_TOKEN / GH_TOKEN. Set it in the shell, not in a file.");
  console.error(`Target: https://github.com/${owner}/${repo}`);
  process.exit(1);
}

const redact = (value) => String(value ?? "").split(token).join("<redacted>");
const headers = {
  Authorization: `Bearer ${token}`,
  Accept: "application/vnd.github+json",
  "User-Agent": "mygo-ximalaya-web-plugin",
  "X-GitHub-Api-Version": "2022-11-28",
};

async function api(path, options = {}) {
  const response = await fetch(API + path, { ...options, headers: { ...headers, ...(options.headers || {}) }, signal: AbortSignal.timeout(20000) });
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: response.status, body };
}

// This host's HTTP proxy rejects CONNECT to github.com (502) while direct
// access works. Git inherits the proxy variables, so drop them for git only.
// Set GITHUB_USE_PROXY=1 to force the proxy path instead.
const gitEnv = { ...process.env, GIT_TERMINAL_PROMPT: "0" };
if (process.env.GITHUB_USE_PROXY !== "1") {
  for (const key of ["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"]) delete gitEnv[key];
}

function git(args, allowFailure = false) {
  const result = spawnSync("git", args, {
    cwd: ROOT,
    encoding: "utf8",
    env: gitEnv,
  });
  if (result.status !== 0 && !allowFailure) {
    throw new Error(`git ${args[0]} failed: ${redact(result.stderr || result.stdout)}`);
  }
  return redact(result.stdout || "").trim();
}

const me = await api("/user");
if (me.status !== 200) throw new Error(`GitHub authentication failed (HTTP ${me.status}): ${redact(JSON.stringify(me.body))}`);
console.log(`authenticated as: ${me.body.login}`);

const target = `/repos/${owner}/${repo}`;
let existing = await api(target);
if (existing.status === 404) {
  const createPath = me.body.login.toLowerCase() === owner.toLowerCase() ? "/user/repos" : `/orgs/${owner}/repos`;
  const created = await api(createPath, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: repo,
      description: "MyGo Music plugin for www.ximalaya.com web features: search, albums, rankings, categories, subscriptions and streaming.",
      private: isPrivate,
      has_issues: true,
      has_projects: false,
      has_wiki: false,
      auto_init: false,
    }),
  });
  if (created.status !== 201) throw new Error(`Repository creation failed (HTTP ${created.status}): ${redact(JSON.stringify(created.body))}`);
  console.log(`created: ${created.body.full_name} (${created.body.html_url})`);
} else if (existing.status === 200) {
  console.log(`repository exists: ${existing.body.full_name} (${existing.body.html_url})`);
} else {
  throw new Error(`Unexpected repository lookup (HTTP ${existing.status}): ${redact(JSON.stringify(existing.body))}`);
}

const publicURL = `https://github.com/${owner}/${repo}.git`;
git(["remote", "remove", "origin"], true);
git(["remote", "add", "origin", publicURL]);
git(["config", "branch.main.remote", "origin"]);
git(["config", "branch.main.merge", "refs/heads/main"]);

// Token appears only in this one child process argument, never in .git/config.
const pushURL = `https://x-access-token:${token}@github.com/${owner}/${repo}.git`;
const push = spawnSync("git", ["-c", "http.proxy=", "-c", "https.proxy=", "push", pushURL, "HEAD:refs/heads/main"], {
  cwd: ROOT,
  encoding: "utf8",
  env: gitEnv,
});
if (push.status !== 0) throw new Error(`git push failed: ${redact(push.stderr || push.stdout)}`);
console.log(redact(push.stderr || push.stdout).trim());

const head = await api(`${target}/commits/main`);
if (head.status !== 200) throw new Error(`Cannot verify remote HEAD (HTTP ${head.status})`);
const local = git(["rev-parse", "HEAD"]);
console.log(`remote HEAD: ${head.body.sha}`);
console.log(`local  HEAD: ${local}`);
console.log(head.body.sha === local ? "VERIFIED: remote matches local commit" : "WARNING: remote HEAD differs from local commit");
console.log(`url: https://github.com/${owner}/${repo}`);

// "Download APK" direct-link endpoint: 302 to the latest stable release's universal APK.
// The asset name format xencode-universal-<tag>.apk is decided by the Rename step in .github/workflows/android-release.yml; if it changes, update this in sync.
const REPO = 'Resolutefemi/XenCode'
const LATEST_PAGE = `https://github.com/${REPO}/releases/latest`
// Downloading GitHub assets directly from mainland China is often crawling-slow, so a gh-proxy mirror layer is applied by default; if the mirror probe fails (offline/broken), fall back to the original GitHub URL.
const MIRROR_PREFIX = 'https://v6.gh-proxy.org/'
const UA = 'xencode-docs-download'
// Per-request cap to keep cross-border slow links from dragging the function to the platform timeout.
const TIMEOUT_MS = 5000

function request(url, method = 'GET') {
  return fetch(url, {
    method,
    redirect: 'manual',
    headers: { 'user-agent': UA },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
}

async function reachable(url) {
  try {
    return (await request(url, 'HEAD')).status < 400
  } catch {
    return false
  }
}

async function resolveApkUrl() {
  // Skips the GitHub API: the unauthenticated endpoint is rate-limited to 60 requests/hour per IP, and Vercel's egress IPs are shared across projects.
  // /releases/latest 302s to /releases/tag/<tag>; grab the version from Location, and latest naturally skips pre-releases.
  const page = await request(LATEST_PAGE)
  const tag = page.headers.get('location')?.match(/\/releases\/tag\/([^/?#]+)$/)?.[1]
  if (!tag) return null

  const githubUrl = `https://github.com/${REPO}/releases/download/${tag}/xencode-universal-${tag}.apk`
  if (await reachable(MIRROR_PREFIX + githubUrl)) return MIRROR_PREFIX + githubUrl
  return (await reachable(githubUrl)) ? githubUrl : null
}

export default async function handler(req, res) {
  let url = null
  try {
    url = await resolveApkUrl()
  } catch {
    url = null
  }

  const cacheControl = url
    ? 'public, s-maxage=1800, stale-while-revalidate=86400'
    : 'no-store'
  res.writeHead(302, {
    Location: url || LATEST_PAGE,
    'Cache-Control': cacheControl,
  })
  res.end()
}

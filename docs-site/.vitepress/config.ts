import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitepress'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  srcDir: './docs',
  lang: 'en-US',
  title: 'XenCode',
  titleTemplate: ':title | XenCode - AI Coding Tool & Terminal on Android',
  description: 'XenCode is an open-source AI coding and mobile workstation tool for Android. It ships with a built-in Linux container and terminal, letting the AI Agent read and write files, run shell commands and builds on its own — with deep integration of the MCP protocol, Git version control and remote SSH development.',
  cleanUrls: true,
  lastUpdated: true,
  sitemap: {
    hostname: 'https://xencode.murk.top'
  },
  head: [
    ['link', { rel: 'icon', href: '/logo.png', type: 'image/png' }],
    ['meta', { name: 'theme-color', content: '#3c8772' }],
    ['meta', { name: 'keywords', content: 'XenCode, Android AI coding, mobile development, Linux terminal, PRoot, Termux, AI Agent, MCP, code editor, Git client, mobile workstation, remote SSH' }],
    ['meta', { name: 'author', content: 'XenCode Team' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'XenCode' }],
    ['meta', { property: 'og:title', content: 'XenCode - AI Coding Tool & Linux Terminal on Android' }],
    ['meta', { property: 'og:description', content: 'An open-source AI coding tool for Android with a built-in Linux container and terminal. AI Agent edits code, runs commands and builds; supports MCP tools, Git and remote SSH.' }],
    ['meta', { property: 'og:image', content: 'https://xencode.murk.top/logo.png' }],
    ['meta', { property: 'og:url', content: 'https://xencode.murk.top/' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'XenCode - AI Coding Tool & Linux Terminal on Android' }],
    ['meta', { name: 'twitter:description', content: 'An open-source AI coding tool for Android with a built-in Linux container and terminal. AI Agent edits code, runs commands and builds; supports MCP tools, Git and remote SSH.' }],
    ['meta', { name: 'twitter:image', content: 'https://xencode.murk.top/logo.png' }]
  ],
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/quick-start', activeMatch: '/guide/' },
      { text: 'Advanced', link: '/advanced/build-android-app', activeMatch: '/advanced/' },
      { text: 'Download', link: 'https://github.com/Resolutefemi/XenCode/releases/latest' }
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Quick Start', link: '/guide/quick-start' },
            { text: 'Feature Overview', link: '/guide/overview' },
            { text: 'Desktop & CLI (PC)', link: '/guide/pc' },
            { text: 'Video Tutorials', link: '/guide/video-tutorials' },
            { text: 'Free Vendors', link: '/guide/free-providers' }
          ]
        },
        {
          text: 'Core Workflows',
          items: [
            { text: 'Chat Interface Tour', link: '/guide/chat' },
            { text: 'Three Modes (Build / Plan / Auto)', link: '/guide/modes' },
            { text: 'Checkpoints & Undo', link: '/guide/checkpoint' },
            { text: 'Terminal', link: '/guide/terminal' },
            { text: 'Built-in Browser', link: '/guide/browser' },
            { text: 'Network Requests', link: '/guide/network-request' },
            { text: 'File Browsing & Code Editing', link: '/guide/files' },
            { text: 'Git Version Control', link: '/guide/git' },
            { text: 'Tablet & Large Screens', link: '/guide/tablet' }
          ]
        },
        {
          text: 'Models & Usage',
          items: [
            { text: 'AI Vendors & Models', link: '/guide/providers' },
            { text: 'Default & Specialized Models', link: '/guide/default-models' },
            { text: 'Token Stats & Costs', link: '/guide/token-stats' }
          ]
        },
        {
          text: 'Execution Environments',
          items: [
            { text: 'Containers & Images', link: '/guide/container' },
            { text: 'Remote SSH Mode', link: '/guide/remote-ssh' },
            { text: 'Remote Environments', link: '/guide/remote-environments' },
            { text: 'Workspace Sync', link: '/guide/sync' },
            { text: 'Network Proxy', link: '/guide/proxy' }
          ]
        },
        {
          text: 'Extensibility',
          items: [
            { text: 'MCP Servers', link: '/guide/mcp' },
            { text: 'Skills', link: '/guide/skills' },
            { text: 'Subagents', link: '/guide/subagent' },
            { text: 'Shizuku Tool', link: '/guide/shizuku' },
            { text: 'Available Tools', link: '/guide/available-tools' },
            { text: 'Custom Prompts', link: '/guide/custom-prompts' },
            { text: 'Memory & Project Rules', link: '/guide/memory' }
          ]
        },
        {
          text: 'Settings & Maintenance',
          items: [
            { text: 'General Settings', link: '/guide/general' },
            { text: 'Appearance & Language', link: '/guide/appearance' },
            { text: 'Tool Permissions', link: '/guide/permissions' },
            { text: 'App Permissions', link: '/guide/app-permissions' },
            { text: 'Running in the Background', link: '/guide/background-run' },
            { text: 'Logs & Troubleshooting', link: '/guide/logs' },
            { text: 'Common Error Codes', link: '/guide/error-codes' },
            { text: 'Storage', link: '/guide/storage' },
            { text: 'Backup & Restore', link: '/guide/backup' },
            { text: 'About & Updates', link: '/guide/about' }
          ]
        }
      ],
      '/advanced/': [
        {
          text: 'Environment Setup',
          items: [
            { text: 'Building Android Apps in a Container', link: '/advanced/build-android-app' },
            { text: 'Building Flutter Apps in a Container', link: '/advanced/build-flutter-app' },
            { text: 'Installing Playwright Browser Automation', link: '/advanced/playwright-mcp' }
          ]
        },
        {
          text: 'Device Debugging',
          items: [
            { text: 'Wireless ADB Debugging for Other Devices', link: '/advanced/adb-wireless-debug' }
          ]
        },
        {
          text: 'Extension Development',
          items: [
            { text: 'Custom Dashboard Cards', link: '/advanced/dashboard-cards' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Resolutefemi/XenCode' }
    ],
    search: {
      provider: 'local'
    },
    outline: { label: 'On this page', level: [2, 3] },
    docFooter: { prev: 'Previous', next: 'Next' },
    lastUpdatedText: 'Last updated',
    darkModeSwitchLabel: 'Theme',
    lightModeSwitchTitle: 'Switch to light mode',
    darkModeSwitchTitle: 'Switch to dark mode',
    sidebarMenuLabel: 'Menu',
    returnToTopLabel: 'Back to top',
    editLink: {
      pattern: 'https://github.com/Resolutefemi/XenCode/edit/main/docs-site/docs/:path',
      text: 'Edit this page on GitHub'
    },
    footer: {
      message: 'Open source under GPL-3.0',
      copyright: 'Copyright © 2026-present XenCode'
    }
  },
  vite: {
    plugins: [
      {
        name: 'serve-raw-md',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url && req.url.startsWith('/md/')) {
              const rel = req.url.slice(4).split('?')[0].split('#')[0]
              const filePath = resolve(__dirname, '../docs', decodeURIComponent(rel))
              if (existsSync(filePath) && statSync(filePath).isFile()) {
                res.setHeader('Content-Type', 'text/plain; charset=utf-8')
                res.setHeader('Access-Control-Allow-Origin', '*')
                res.end(readFileSync(filePath, 'utf-8'))
                return
              }
            }
            next()
          })
        },
        configurePreviewServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url && req.url.startsWith('/md/')) {
              const rel = req.url.slice(4).split('?')[0].split('#')[0]
              const filePath = resolve(__dirname, 'dist/md', decodeURIComponent(rel))
              if (existsSync(filePath) && statSync(filePath).isFile()) {
                res.setHeader('Content-Type', 'text/plain; charset=utf-8')
                res.setHeader('Access-Control-Allow-Origin', '*')
                res.end(readFileSync(filePath, 'utf-8'))
                return
              }
            }
            next()
          })
        }
      }
    ]
  }
})

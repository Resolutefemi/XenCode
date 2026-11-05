---
layout: home
title: XenCode - AI Coding Tool for Android with a Built-in Linux Terminal
titleTemplate: false
description: XenCode is an open-source AI coding tool for Android. No computer needed — complete the entire development workflow right on your phone. With a built-in Linux container and terminal, the AI Agent can read and write code and run builds directly; Git version control, the MCP protocol, and remote SSH development are supported.
head:
  - - meta
    - name: keywords
      content: XenCode, Android AI coding, mobile coding, mobile development, Linux terminal, PRoot, Termux, AI Agent, MCP protocol, mobile code editor, Git client, mobile workstation, terminal on your phone, remote SSH development

hero:
  name: XenCode
  text: AI Coding Tool and Mobile Workstation on Your Phone
  tagline: No computer needed — complete the full development workflow on your phone, from writing and debugging code to committing it. With a built-in Linux container and terminal, the AI Agent autonomously reads and writes files, runs shell commands, and executes builds. Git, MCP, remote SSH, and a full model ecosystem are also supported.
  image:
    src: /logo.png
    alt: XenCode
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: Download APK
      link: https://xencode.murk.top/download
    - theme: alt
      text: GitHub
      link: https://github.com/Resolutefemi/XenCode

features:
  - title: AI Agent
    details: Supports OpenAI / Anthropic / Gemini compatible protocols with instant switching between providers; built-in tools include file read/write, shell execution, a background terminal, search, and image recognition; streaming output with automatic context compaction for long conversations.
  - title: Built-in Terminal and Linux Container
    details: A local container built on Termux components and PRoot with an Alpine image included; import a custom rootfs, mount host directories, and keep the terminal alive in the background.
  - title: Remote SSH Mode
    details: Uses a remote server as the execution backend — commands over an exec channel, files over SFTP, and the terminal over a shell channel — so you can work on remote projects right from your phone.
  - title: Checkpoints and Undo
    details: Automatically snapshots files before the AI edits code; roll back with one tap mid-conversation — restore code only, conversation only, or both at once.
  - title: MCP, Skills, and Memory
    details: Connect local stdio and remote HTTP MCP servers to extend tools on the fly; global and project-level skills plus long-term memory let the AI reuse experience across sessions.
  - title: Git and Workspace Sync
    details: Built-in status, branch, commit, diff, and tag management; supports SFTP / FTP sync and ships an FTP server for managing files from your computer.
---

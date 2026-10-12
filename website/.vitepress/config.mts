import { defineConfig } from 'vitepress'

const repo = 'https://github.com/medievalrp-net/Spyglass'

export default defineConfig({
  title: 'Spyglass',
  description: 'Logging and rollback for Paper servers.',
  // Served from GitHub Pages at medievalrp-net.github.io/Spyglass/.
  base: '/Spyglass/',
  cleanUrls: true,
  lastUpdated: true,
  head: [['meta', { name: 'theme-color', content: '#3b6fd8' }]],
  // config.conf is HOCON, which Shiki doesn't ship; HCL is close enough.
  markdown: { languageAlias: { hocon: 'hcl' } },

  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/installation', activeMatch: '/guide/' },
      { text: 'Reference', link: '/reference/commands', activeMatch: '/reference/' },
      { text: 'Download', link: `${repo}/releases/latest` },
    ],

    sidebar: [
      {
        text: 'Getting started',
        items: [
          { text: 'Installation', link: '/guide/installation' },
          { text: 'Quick start', link: '/guide/quick-start' },
        ],
      },
      {
        text: 'Using Spyglass',
        items: [
          { text: 'Inspecting blocks', link: '/guide/inspecting' },
          { text: 'Searching', link: '/guide/searching' },
          { text: 'Rollback, restore and undo', link: '/guide/rollback' },
          { text: 'Recovering items (salvage)', link: '/guide/salvage' },
          { text: 'Inventory snapshots', link: '/guide/snapshots' },
        ],
      },
      {
        text: 'Running a server',
        items: [
          { text: 'Choosing a database', link: '/guide/databases' },
          { text: 'Importing from CoreProtect', link: '/guide/coreprotect' },
          { text: 'Cross-server search (Velocity)', link: '/guide/velocity' },
          { text: 'Reloading and updates', link: '/guide/maintenance' },
          { text: 'Troubleshooting', link: '/guide/troubleshooting' },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Commands', link: '/reference/commands' },
          { text: 'Search keys and flags', link: '/reference/query' },
          { text: 'Permissions', link: '/reference/permissions' },
          { text: 'Configuration', link: '/reference/configuration' },
          { text: 'Events', link: '/reference/events' },
          { text: 'Developer API', link: '/reference/api' },
        ],
      },
    ],

    search: { provider: 'local' },

    socialLinks: [
      { icon: 'github', link: repo },
      { icon: 'discord', link: 'https://discord.gg/XkpVHcHvH' },
    ],

    editLink: {
      pattern: `${repo}/edit/main/website/:path`,
      text: 'Edit this page on GitHub',
    },

    footer: {
      message: 'The plugin is GPL-3.0; the API is Apache-2.0.',
      copyright: 'Spyglass contributors',
    },
  },
})

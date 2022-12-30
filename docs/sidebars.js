const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO AWS",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "artifacts",
        "wrappers",
        "configuration",
        "http",
        "aspects",
        "examples",
        "changelog",
        "migration-guide",
      ]
    }
  ]
};

module.exports = sidebars;

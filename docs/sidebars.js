const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "Getting Started",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        {
          type: "category",
          label: "Reference",
          collapsed: false,
          items: [
            "artifacts",
            "wrappers",
            "configuration",
            "http",
            "aspects",
            "aws-error",
          ],
        },
        {
          type: "category",
          label: "Tutorial",
          collapsed: false,
          link: { type: "doc", id: "tutorial-first-program" },
          items: ["examples"],
        },
        {
          type: "category",
          label: "How-to Guides",
          collapsed: false,
          items: [
            "guide-configure-credentials-and-region",
            "guide-choose-http-client",
            "guide-paginate-and-stream",
            "guide-handle-errors",
            "guide-add-aspects",
            "guide-test-code-with-stubs",
          ],
        },
        {
          type: "category",
          label: "Changelog and Migration",
          collapsed: false,
          link: { type: "doc", id: "changelog" },
          items: ["migration-guide"],
        },
      ],
    },
  ],
};

module.exports = sidebars;

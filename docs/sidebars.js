const sidebars = {
  sidebar: [
    "index",
    "getting-started",
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
    "tutorial-first-program",
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "guide-configure-credentials-and-region",
        "guide-use-a-local-endpoint",
        "guide-choose-http-client",
        "guide-paginate-and-stream",
        "guide-stream-s3-objects",
        "guide-handle-errors",
        "guide-add-retries",
        "guide-add-aspects",
        "guide-call-the-sdk-client-directly",
        "guide-test-code-with-stubs",
      ],
    },
    {
      type: "category",
      label: "Resources",
      collapsed: true,
      items: ["resources", "examples", "changelog", "migration-guide"],
    },
  ],
};

module.exports = sidebars;

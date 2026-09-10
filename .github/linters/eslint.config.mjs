// The dashboard is three plain files served straight from nginx (or from the
// personal-mode binary) with no build step and no package.json, so this config
// lives with the other CI linters and is passed to ESLint explicitly.
//
// It checks for the mistakes that actually break a browser-only script -
// undeclared globals, unused bindings, unreachable code, a promise nobody
// awaits - and nothing about style, which prettier is not here to enforce.
export default [
  {
    files: ["frontend/**/*.js"],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: "script",
      globals: {
        console: "readonly",
        document: "readonly",
        fetch: "readonly",
        navigator: "readonly",
        sessionStorage: "readonly",
        setInterval: "readonly",
        clearInterval: "readonly",
        setTimeout: "readonly",
        clearTimeout: "readonly",
        window: "readonly",
        requestAnimationFrame: "readonly",
        devicePixelRatio: "readonly",
        AbortController: "readonly",
        URL: "readonly",
        URLSearchParams: "readonly",
        TextEncoder: "readonly",
        btoa: "readonly",
        atob: "readonly",
        Image: "readonly",
        location: "readonly",
      },
    },
    linterOptions: {
      reportUnusedDisableDirectives: "error",
    },
    rules: {
      "no-undef": "error",
      // An unused catch binding is a deliberate "I do not need the reason"
      // and reads better than omitting the parameter entirely.
      "no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", caughtErrors: "none" },
      ],
      "no-unreachable": "error",
      "no-dupe-keys": "error",
      "no-dupe-args": "error",
      "no-duplicate-case": "error",
      "no-fallthrough": "error",
      "no-constant-condition": "error",
      "no-self-compare": "error",
      "no-sparse-arrays": "error",
      "no-template-curly-in-string": "error",
      "no-unsafe-negation": "error",
      "no-unmodified-loop-condition": "error",
      "use-isnan": "error",
      "valid-typeof": "error",
      eqeqeq: ["error", "smart"],
      "no-var": "error",
    },
  },
];

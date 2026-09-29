import js from '@eslint/js';
import i18next from 'eslint-plugin-i18next';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'dev-dist', 'coverage', 'playwright-report', 'test-results', 'public'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.strictTypeChecked],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      globals: globals.browser,
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: { 'react-hooks': reactHooks, 'jsx-a11y': jsxA11y },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.strict.rules,
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      // Tokens must never be persisted in browser storage (docs/SECURITY.md, Issue #3 review).
      'no-restricted-syntax': [
        'error',
        {
          selector: 'MemberExpression[property.name=/^(localStorage|sessionStorage)$/]',
          message:
            'Browser storage is restricted: preferences go through src/app/preferences.ts; tokens are never stored.',
        },
        {
          selector: 'MemberExpression[object.name=/^(localStorage|sessionStorage)$/]',
          message:
            'Browser storage is restricted: preferences go through src/app/preferences.ts; tokens are never stored.',
        },
      ],
    },
  },
  {
    // No hard-coded user-facing text in application code (docs/I18N.md rule 1).
    files: ['src/**/*.tsx'],
    ignores: ['src/**/*.test.tsx', 'src/test/**'],
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': [
        'error',
        {
          mode: 'jsx-only',
          'jsx-attributes': { include: ['alt', 'aria-label', 'title', 'placeholder'] },
        },
      ],
    },
  },
  {
    // Reviewed exceptions: UI preferences (localStorage) and the transient PKCE state store
    // (sessionStorage). Tests may inspect storage to prove tokens are absent.
    files: [
      'src/app/preferences.ts',
      'src/auth/oidc.ts',
      '**/*.test.{ts,tsx}',
      'src/test/**',
      'e2e/**',
    ],
    rules: { 'no-restricted-syntax': 'off' },
  },
  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**', 'e2e/**'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
    },
  },
);

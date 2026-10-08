import { createSsoDir, removeSsoDir, SSO_DIR_ENV } from './sso-state.ts';

/**
 * DEVX-001A (A75-3): the SSO state directory of this run. The suite runner (run-suite.sh) normally
 * creates it and removes it on exit; a plain `playwright test` gets its own here, removed by the
 * returned teardown. Workers inherit the variable.
 */
export default function globalSetup(): () => void {
  if (process.env[SSO_DIR_ENV]) return () => undefined;
  const dir = createSsoDir();
  process.env[SSO_DIR_ENV] = dir;
  return () => {
    removeSsoDir(dir);
  };
}

import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';

// The page moves focus in requestAnimationFrame callbacks. jsdom runs them on a real ~16 ms frame
// clock, so on a busy machine a pending callback could fire in the middle of a test's next
// interaction (for example, stealing focus while it types). Running them on the next tick makes
// them land before the test's next step, every time, on every machine.
window.requestAnimationFrame = (callback) =>
  window.setTimeout(() => {
    callback(performance.now());
  }, 0);
window.cancelAnimationFrame = (handle) => {
  window.clearTimeout(handle);
};

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
});

afterEach(() => {
  cleanup();
});

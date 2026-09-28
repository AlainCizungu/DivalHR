// Local development defaults. The container image regenerates this file from environment
// variables at start-up. Contains public configuration only: never secrets.
window.__DIVALHR_CONFIG__ = {
  environment: 'development',
  coreApiUrl: 'http://localhost:8080/api/v1',
  aiServiceUrl: 'http://localhost:8090/api/v1',
  oidcAuthority: 'http://localhost:8180/realms/divalhr-dev',
  oidcClientId: 'divalhr-web',
};

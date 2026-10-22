#!/usr/bin/env python3
"""R157-1 (MVP-101, ADR 0027): provisions the Core -> AI service client in the test realm.

    keycloak_ai_client.py <admin base url> <secrets dir>

Run by keycloak-ai-client.sh on the instance, through the operator site on the instance's
loopback, never through the public origin. A realm is imported only on Keycloak's first start, so
the capability is ensured here, idempotently, on every deployment. It creates or reconciles:

1. the capability client `divalhr-ai-service` (no flow; it never authenticates) and its client
   role `generate-operational-summary`;
2. the confidential client `divalhr-core-ai`: client credentials only, restricted scope, access
   tokens of 120 seconds, an audience mapper for `divalhr-ai-service`, and the client secret read
   from <secrets>/ops/ai-service-client-secret (root, 0400);
3. its service account's one client-role mapping and the matching scope mapping, so the role
   reaches the token and nothing else does.

The secret is never printed, logged or put in an argument: it is read from the file and sent only
in the admin request body. Prints step names only.
"""

import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from keycloak_admin_setup import call, fail, read_env, token_for_retrying  # noqa: E402

REALM = "divalhr-test"
CAPABILITY_CLIENT = "divalhr-ai-service"
CALLER_CLIENT = "divalhr-core-ai"
ROLE = "generate-operational-summary"
MIN_SECRET = 32


def capability_representation():
    return {
        "clientId": CAPABILITY_CLIENT,
        "name": "DivalHR AI service capability (MVP-101)",
        "description": "Audience and capability role of the AI service. No flow is enabled; it never authenticates.",
        "enabled": True,
        "publicClient": False,
        "bearerOnly": False,
        "serviceAccountsEnabled": False,
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "protocol": "openid-connect",
        "fullScopeAllowed": False,
        "attributes": {"oauth2.device.authorization.grant.enabled": "false"},
    }


def caller_representation(secret):
    return {
        "clientId": CALLER_CLIENT,
        "name": "DivalHR Core API to AI service (MVP-101)",
        "enabled": True,
        "publicClient": False,
        "clientAuthenticatorType": "client-secret",
        "secret": secret,
        "serviceAccountsEnabled": True,
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "protocol": "openid-connect",
        "fullScopeAllowed": False,
        "attributes": {
            "access.token.lifespan": "120",
            "oauth2.device.authorization.grant.enabled": "false",
        },
        "defaultClientScopes": ["service_account", "basic", "roles"],
        "optionalClientScopes": [],
        "protocolMappers": [
            {
                "name": "divalhr-ai-service-audience",
                "protocol": "openid-connect",
                "protocolMapper": "oidc-audience-mapper",
                "consentRequired": False,
                "config": {
                    "included.custom.audience": CAPABILITY_CLIENT,
                    "access.token.claim": "true",
                    "id.token.claim": "false",
                    "introspection.token.claim": "true",
                    "lightweight.claim": "false",
                },
            }
        ],
    }


def find_client(realm, token, client_id):
    status, found, _ = call(
        "GET", f"{realm}/clients?clientId={urllib.parse.quote(client_id)}&search=false", token
    )
    if status != 200:
        fail(f"looking up a client returned {status}")
    return found[0] if found else None


def ensure_client(realm, token, representation, label):
    existing = find_client(realm, token, representation["clientId"])
    if existing is None:
        status, _, _ = call("POST", f"{realm}/clients", token, body=representation)
        if status != 201:
            fail(f"creating the {label} client returned {status}")
        print(f"{label}: created")
    else:
        # Reconcile every field (flows, scope, lifespan, mappers, secret) so drift cannot stay.
        merged = {**existing, **representation}
        status, _, _ = call("PUT", f"{realm}/clients/{existing['id']}", token, body=merged)
        if status != 204:
            fail(f"reconciling the {label} client returned {status}")
        print(f"{label}: reconciled")
    client = find_client(realm, token, representation["clientId"])
    if client is None:
        fail(f"the {label} client is missing after it was written")
    return client


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: keycloak_ai_client.py <admin base url> <secrets dir>")
    base = sys.argv[1].rstrip("/")
    secrets_dir = sys.argv[2]
    secret_file = os.path.join(secrets_dir, "ops", "ai-service-client-secret")
    env_file = os.path.join(secrets_dir, "ops", "kc-admin.env")
    if not os.path.exists(secret_file):
        fail("secrets/ops/ai-service-client-secret is missing: run init-secrets.sh")
    if not os.path.exists(env_file):
        fail("no kc-admin.env: run keycloak-admin-setup.sh first")
    with open(secret_file, encoding="utf-8") as handle:
        secret = handle.read().strip()
    if len(secret) < MIN_SECRET or secret.lower().startswith("dev-only"):
        fail("the AI service client secret is too short or a development value")
    values = read_env(env_file)
    token = token_for_retrying(base, values.get("KC_ADMIN_USER", ""), values.get("KC_ADMIN_PASSWORD", ""))
    if not token:
        fail("the permanent operator cannot sign in")
    realm = f"{base}/admin/realms/{REALM}"

    capability = ensure_client(realm, token, capability_representation(), "capability client")
    status, role, _ = call(
        "GET", f"{realm}/clients/{capability['id']}/roles/{urllib.parse.quote(ROLE)}", token
    )
    if status == 404:
        status, _, _ = call(
            "POST",
            f"{realm}/clients/{capability['id']}/roles",
            token,
            body={
                "name": ROLE,
                "description": "The only right of divalhr-core-ai: request a grounded operational summary plan (MVP-101).",
                "composite": False,
                "clientRole": True,
            },
        )
        if status != 201:
            fail(f"creating the capability role returned {status}")
        print("capability role: created")
        status, role, _ = call(
            "GET", f"{realm}/clients/{capability['id']}/roles/{urllib.parse.quote(ROLE)}", token
        )
    elif status == 200:
        print("capability role: present")
    if status != 200 or not role:
        fail(f"reading the capability role returned {status}")

    caller = ensure_client(realm, token, caller_representation(secret), "caller client")

    status, account, _ = call("GET", f"{realm}/clients/{caller['id']}/service-account-user", token)
    if status != 200 or not account:
        fail(f"reading the service account returned {status}")
    status, mapped, _ = call(
        "GET", f"{realm}/users/{account['id']}/role-mappings/clients/{capability['id']}", token
    )
    if status != 200:
        fail(f"reading the service account roles returned {status}")
    if not any(item.get("name") == ROLE for item in mapped or []):
        status, _, _ = call(
            "POST", f"{realm}/users/{account['id']}/role-mappings/clients/{capability['id']}", token, body=[role]
        )
        if status != 204:
            fail(f"granting the capability role returned {status}")
        print("service account role: granted")
    else:
        print("service account role: present")

    status, scoped, _ = call(
        "GET", f"{realm}/clients/{caller['id']}/scope-mappings/clients/{capability['id']}", token
    )
    if status != 200:
        fail(f"reading the scope mapping returned {status}")
    if not any(item.get("name") == ROLE for item in scoped or []):
        status, _, _ = call(
            "POST", f"{realm}/clients/{caller['id']}/scope-mappings/clients/{capability['id']}", token, body=[role]
        )
        if status != 204:
            fail(f"mapping the capability role into the client scope returned {status}")
        print("scope mapping: added")
    else:
        print("scope mapping: present")

    # No direct client-role mapping other than the one capability (in particular no
    # realm-management role); Keycloak's own default-roles composite is not a direct mapping.
    status, all_roles, _ = call("GET", f"{realm}/users/{account['id']}/role-mappings", token)
    if status != 200:
        fail(f"reading all role mappings returned {status}")
    client_maps = (all_roles or {}).get("clientMappings") or {}
    granted = {
        (name, item["name"]) for name, entry in client_maps.items() for item in entry.get("mappings", [])
    }
    if granted != {(CAPABILITY_CLIENT, ROLE)}:
        fail("the AI client's service account holds a client role other than the one capability")
    print("capability: verified")


if __name__ == "__main__":
    main()

#!/bin/bash

# Test logout URL generation
# This simulates what the logout() method should generate

BASE_PATH="/local-llm"
OIDC_ISSUER="https://133.39.114.45:7443/local-llm-auth/realms/local-llm"
OIDC_REDIRECT_BASE_URL="https://133.39.114.45:7443"

# Expected values
END_SESSION_URL="${OIDC_ISSUER}/protocol/openid-connect/logout"
POST_LOGOUT_URI="$(printf '%s' "${OIDC_REDIRECT_BASE_URL}${BASE_PATH}/" | jq -sRr @uri)"

echo "=== Logout URL Generation Test ==="
echo ""
echo "Configuration:"
echo "  BASE_PATH: $BASE_PATH"
echo "  OIDC_ISSUER: $OIDC_ISSUER"
echo "  OIDC_REDIRECT_BASE_URL: $OIDC_REDIRECT_BASE_URL"
echo ""

echo "Expected post_logout_redirect_uri:"
echo "  $POST_LOGOUT_URI"
echo ""

echo "Full logout URL:"
echo "${END_SESSION_URL}?client_id=k8s-pups&post_logout_redirect_uri=${POST_LOGOUT_URI}"
echo ""

# Check if URL is valid
echo "Checking if post_logout_redirect_uri matches Keycloak client config..."
echo "This URL must be registered in Keycloak as 'Valid Post Logout Redirect URIs'"
echo ""

# Decode for readability
echo "Decoded post_logout_redirect_uri:"
printf '%s\n' "$POST_LOGOUT_URI" | python3 -c "import sys, urllib.parse; print(urllib.parse.unquote(sys.stdin.read().strip()))" 2>/dev/null || echo "  https://133.39.114.45:7443/local-llm/"

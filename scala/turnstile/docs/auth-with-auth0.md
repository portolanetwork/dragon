# Configuring Auth0 for Dragon MCP Server

This guide walks through configuring Auth0 as the authorization server for Dragon's MCP implementation.

## Overview

Dragon's MCP Gateway includes built-in Auth0 integration with:
- JWT token validation for API authentication
- `.well-known` endpoint proxying for OIDC discovery
- Support for OIDC Dynamic Client Registration

## Prerequisites

- An Auth0 account (create one at [auth0.com](https://auth0.com))
- Admin access to your Auth0 dashboard
- Your Dragon server domain (e.g., `https://your-dragon-server.com`)

## Step 1: Enable OIDC Dynamic Application Registration

1. Log into your [Auth0 dashboard](https://manage.auth0.com)
2. Navigate to **Settings** → **Advanced**
3. Scroll to **OAuth** section
4. Enable **OIDC Dynamic Application Registration**
5. Click **Save Changes**

**Why this matters**: This allows MCP clients (like Claude Desktop) to dynamically register themselves as OAuth2 clients without manual pre-registration.

## Step 2: Create and Configure Your API

### 2.1 Create the API

1. In the Auth0 dashboard, navigate to **Applications** → **APIs**
2. Click **+ Create API**
3. Configure:
   - **Name**: `Dragon MCP Server` (or your preferred name)
   - **Identifier** (Audience): `https://your-dragon-server.com/` or a unique URI like `https://api.dragon.yourcompany.com`
   - **Signing Algorithm**: RS256 (default)
4. Click **Create**

### 2.2 Set Default Audience

1. Navigate to **Settings** → **General**
2. Scroll to **API Authorization Settings**
3. Set **Default Audience** to the API Identifier you created above (e.g., `https://your-dragon-server.com/`)
4. Click **Save Changes**

**Why this matters**: The default audience ensures that issued tokens are scoped to your MCP server, providing proper authorization context.

## Step 3: Configure Authentication Connections

You have two options for enabling authentication connections:

### Option A: Promote Google Login to a Domain Connection (Tenant-Wide)

This approach makes Google OAuth available to all dynamically registered clients tenant-wide.

1. **Grant Management API Permissions**:
   - Go to **Applications** → **Auth0 Management API** → **API** tab
   - Ensure the Management API is authorized
   - Expand the Management API row
   - Check the box for `update:connections` permission
   - Click **Update**

2. **Get Connection Information**:
   - Navigate to **Authentication** → **Social** → **Google**
   - Copy the connection identifier (format: `con_xxxxx`)

3. **Generate Management API Token**:
   - Go to **Applications** → **APIs** → **Auth0 Management API** → **Test** tab
   - Copy the cURL command to generate an access token
   - Execute it to get your `MGMT_API_ACCESS_TOKEN`

4. **Promote Connection**:
   Replace placeholders with your values and run:
   ```bash
   curl --request PATCH \
     --url "https://{YOUR_AUTH0_DOMAIN}/api/v2/connections/{CONNECTION_ID}" \
     --header "Authorization: Bearer {MGMT_API_ACCESS_TOKEN}" \
     --header "Content-Type: application/json" \
     --data '{ "is_domain_connection": true }'
   ```

### Option B: Use Client Registration Action (Per-Client)

This approach automatically enables connections for each newly registered client using an Auth0 Action.

1. Navigate to **Actions** → **Library**
2. Click **+ Build Custom**
3. Select trigger: **Client Credentials Exchange / Post Client Registration**
4. Name it: `Enable Connections for Dynamic Clients`
5. Add the following code:

```javascript
exports.onExecutePostClientRegistration = async (event, api) => {
  const ManagementClient = require('auth0').ManagementClient;

  const management = new ManagementClient({
    domain: event.secrets.domain,
    clientId: event.secrets.clientId,
    clientSecret: event.secrets.clientSecret,
  });

  const clientId = event.client.client_id;

  // Define the connections you want to enable
  const connections = ['Username-Password-Authentication', 'google-oauth2'];

  try {
    for (const connection of connections) {
      await management.connections.update(
        { id: connection },
        { enabled_clients: [...event.connection.enabled_clients, clientId] }
      );
    }
    console.log(`Successfully enabled connections for client: ${clientId}`);
  } catch (error) {
    console.error('Error enabling connections:', error);
  }
};
```

6. Configure secrets in the action:
   - `domain`: Your Auth0 domain (e.g., `your-tenant.us.auth0.com`)
   - `clientId`: Your Management API client ID
   - `clientSecret`: Your Management API client secret

7. Click **Deploy**

**Recommendation**: Option B is more flexible and maintainable for dynamic client scenarios.

## Step 4: Configure Dragon Environment Variables

Set the following environment variables before starting your Dragon server:

```bash
export AUTH0_DOMAIN="your-tenant.us.auth0.com"
export AUTH0_AUDIENCE="https://your-dragon-server.com/"
```

Or add them to your `.env` file:
```properties
AUTH0_DOMAIN=your-tenant.us.auth0.com
AUTH0_AUDIENCE=https://your-dragon-server.com/
```

These values are referenced in `application.conf`:
```hocon
dragon {
  auth {
    auth0 {
      domain = ${?AUTH0_DOMAIN}
      audience = ${?AUTH0_AUDIENCE}
    }
  }
}
```

## Step 5: Verify Configuration

### Test .well-known Endpoint Proxying

Dragon's MCP Gateway automatically proxies `.well-known` requests to your Auth0 domain. This allows MCP clients to discover OIDC configuration without cross-origin issues.

Test it:
```bash
# Should return Auth0's OIDC configuration
curl http://localhost:8082/.well-known/openid-configuration

# Should return Auth0's JSON Web Key Set
curl http://localhost:8082/.well-known/jwks.json
```

### Verify Dynamic Client Registration

```bash
curl -X POST "http://localhost:8082/.well-known/openid-configuration" \
  -H "Content-Type: application/json" \
  -d '{
    "client_name": "Test MCP Client",
    "redirect_uris": ["http://localhost:3000/callback"]
  }'
```

You should receive a response with `client_id` and `client_secret`.

## Architecture Notes

### .well-known Proxy Implementation

The `TurnstileMcpGatewayServer` class includes a `createWellKnown` function that:
- Intercepts requests to `/.well-known/*`
- Proxies them to `https://{AUTH0_DOMAIN}/.well-known/*`
- Returns Auth0's responses transparently
- Handles errors with proper HTTP status codes

**Location**: `src/main/scala/app/dragon/turnstile/gateway/TurnstileMcpGatewayServiceImpl.scala`

### Security Considerations

1. **Token Validation**: Ensure JWT tokens are validated against:
   - Issuer: `https://{AUTH0_DOMAIN}/`
   - Audience: Your configured audience
   - Signature: Using Auth0's public keys from JWKS endpoint

2. **HTTPS**: Always use HTTPS in production. The `.well-known` proxy works over HTTP for local development only.

3. **Rate Limiting**: Consider implementing rate limiting on the `.well-known` endpoints to prevent abuse.

## Troubleshooting

### Issue: "Invalid audience" errors
- **Solution**: Verify `AUTH0_AUDIENCE` matches your API identifier exactly
- Check that tokens include the `aud` claim with your audience value

### Issue: .well-known endpoints return 404
- **Solution**: Ensure `AUTH0_DOMAIN` is set correctly without `https://` prefix
- Verify your Auth0 tenant is active and accessible

### Issue: Dynamic client registration fails
- **Solution**: Check OIDC Dynamic Registration is enabled in Auth0 settings
- Verify your Auth0 plan supports dynamic client registration

### Issue: Google login not available
- **Solution**: Ensure Google connection is configured and enabled
- For Option A: Verify domain connection promotion completed successfully
- For Option B: Check the Action is deployed and executed successfully

## References

- [Aembit: Configuring MCP Server with Auth0](https://aembit.io/blog/configuring-an-mcp-server-with-auth0-as-the-authorization-server/)
- [Auth0 Dynamic Client Registration](https://auth0.com/docs/get-started/applications/dynamic-client-registration)
- [Auth0 Management API](https://auth0.com/docs/api/management/v2)
- [Model Context Protocol Specification](https://spec.modelcontextprotocol.io)

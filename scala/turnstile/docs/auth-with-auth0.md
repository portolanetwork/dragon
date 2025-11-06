- Create a an Auth0 Accounts
- Enable OIDC Dynamic Applicaiton REgistration

1. Log into your Auth0 dashboard.
2. Go to Settings → Advanced.
3. Enable OIDC Dynamic Application Registration.

- Set a default audiance

1. In the Auth0 dashboard, go to APIs
2. Click on + Create API
3. Enter a friendly name under Name (for example, “My MCP Server”)
4. Enter https://mymcpserver.com/ under Identifier
5. Click Save


6. In the Auth0 dashboard, go to Settings → General → Default Audience.
7. Enter your MCP server’s domain, for example: https://mymcpserver.com/

- Promote Google Login to a Domain Conneciton

1. Go to Applications → Auth0 Management API → API tab.
2. Ensure that the Auth0 Management API is authorized.
3. Expand the Auth0 Management API row by clicking the arrow on the right.
4. Check the box for the update:connections permission.
5. Click Update.

6. Go to Authentication → Social → Google in the Auth0 dashboard.
7. Copy the connection identifier (e.g., `con_xxxxx`)
8. Generate a Management API access token (copy curl code from Auth0 dashboard → APIs → Auth0 Management API → Test tab and execute it).
9. Run the following request, replacing placeholders with your values:

curl --request PATCH \
  --url "https://{YOURS_AUTH0_TENANT_ID}.us.auth0.com/api/v2/
connections/{CONNECTION_ID}" \
  --header "Authorization: Bearer {MGMT_API_ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data '{ "is_domain_connection": true }'


- Configure env vars for auth0 domain and audiance



---

This info is take from : https://aembit.io/blog/configuring-an-mcp-server-with-auth0-as-the-authorization-server/

---

Alternative to updating domain connectinos tenant wide is to use a client registraiton actoin:

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
  } catch (error) {
    console.error('Error enabling connections:', error);
  }
};

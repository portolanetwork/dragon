import * as React from 'react';
import Typography from '@mui/material/Typography';
import {Fragment} from "react";

function HelpContent({selectedMenuItem}: { selectedMenuItem: string[] }) {
    const getHelpContent = () => {
        switch (selectedMenuItem.at(0)) {
            case 'MCP Servers':
                return (
                    <>
                        <Typography variant="h6">MCP Servers</Typography>
                        <Typography variant="body1" paragraph>
                            MCP Servers available in available in your account.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>MCP Servers Overview</Typography>
                        <Typography variant="body1" paragraph>
                            The MCP Servers table displays all configured servers, showing their UUID, name, URL,
                            authentication type, transport protocol, token status, and creation date. Click on any
                            server row to view its details and manage its settings.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Adding a New MCP Server</Typography>
                        <Typography variant="body1" paragraph>
                            Click the "Add MCP Server" button to configure a new server.
                        </Typography>
                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Managing MCP Servers</Typography>
                        <Typography variant="body1" paragraph>
                            Click on a server in the table to access its detail page.
                        </Typography>
                    </>
                );
            case 'Edit MCP Server':
                return (
                    <>
                        <Typography variant="h6">MCP Server Details</Typography>
                        <Typography variant="body1" paragraph>
                            This page displays the configuration details for the selected MCP server.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Server Information</Typography>
                        <Typography variant="body1" paragraph>
                            View the server's UUID, name, URL, authentication type, transport protocol, static token
                            status, and creation/update timestamps. For servers using OAuth Discovery authentication,
                            the current connection status is also displayed.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Available Actions</Typography>
                        <Typography variant="subtitle2">Connect/Disconnect</Typography>
                        <Typography variant="body1" paragraph>
                            Available only for servers using OAuth Discovery authentication. Click Connect to open
                            a new window for authentication. Once authenticated, use Disconnect to terminate the
                            connection. The current connection status is shown in the Connection Status field.
                        </Typography>
                        <Typography variant="subtitle2">Load Tools</Typography>
                        <Typography variant="body1" paragraph>
                            Activate this MCP server's tools, making them available to your upstream AI assistant.
                        </Typography>
                        <Typography variant="subtitle2">Unload Tools</Typography>
                        <Typography variant="body1" paragraph>
                            Deactivate this server's tools.
                        </Typography>
                        <Typography variant="subtitle2">Delete</Typography>
                        <Typography variant="body1" paragraph>
                            Permanently remove this server configuration. You will be prompted to confirm before
                            deletion. This action cannot be undone.
                        </Typography>
                    </>
                );
            case 'Add MCP Server':
                return (
                    <>
                        <Typography variant="h6">Add MCP Server</Typography>
                        <Typography variant="body1" paragraph>
                            Configure a new MCP (Model Context Protocol) server to extend your AI assistant's
                            capabilities with additional tools and resources.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Required Fields</Typography>
                        <Typography variant="subtitle2">Name</Typography>
                        <Typography variant="body1" paragraph>
                            Provide a friendly, descriptive name for this MCP server. This name will be used to
                            identify the server in the MCP Servers list.
                        </Typography>
                        <Typography variant="subtitle2">URL</Typography>
                        <Typography variant="body1" paragraph>
                            Enter the full URL endpoint of the MCP server (e.g., https://example.com/mcp).
                        </Typography>
                        <Typography variant="subtitle2">Authentication Type</Typography>
                        <Typography variant="body1" paragraph>
                            Select the authentication method required by the server:
                        </Typography>
                        <Typography variant="body1" paragraph>
                            <strong>None:</strong> No authentication required. The server is publicly accessible.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            <strong>OAuth Discovery:</strong> Uses OAuth for authentication. After adding the server,
                            you'll need to connect and authenticate through the OAuth provider.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            <strong>Static Header:</strong> Uses a static authentication token. When selected, you
                            must provide the token in the Static Token field that appears below.
                        </Typography>
                        <Typography variant="subtitle2">Transport Type</Typography>
                        <Typography variant="body1" paragraph>
                            Select the communication protocol. Currently, only Streaming HTTP is supported for
                            real-time communication with the MCP server.
                        </Typography>
                        <Typography variant="subtitle2">Static Token (conditional)</Typography>
                        <Typography variant="body1" paragraph>
                            This field appears only when Static Header authentication is selected. Enter the
                            authentication token provided by the MCP server administrator. This token will be
                            stored securely and used for all requests to the server.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Saving</Typography>
                        <Typography variant="body1" paragraph>
                            Click "Add Server" to save the configuration. After successful creation, you'll be
                            redirected back to the MCP Servers list where you can manage the new server. Click
                            "Cancel" to return without saving.
                        </Typography>
                    </>
                );

            case 'Settings':
                return (
                    <>
                        <Typography variant="h6">Settings</Typography>
                        <Typography variant="subtitle2">Theme</Typography>
                        <Typography variant="body1" paragraph>
                            Select between Light and Dark themes for the console.
                        </Typography>
                    </>
                );

            default:
                return 'Select a menu item to see help content';
        }
    };

    return (
        <Typography variant="body1">{getHelpContent()}</Typography>
    );
}

export default HelpContent;
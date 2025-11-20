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
                            MCP Servers available in your account.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Overview</Typography>
                        <Typography variant="body1" paragraph>
                            View all configured servers with their UUID, name, URL, authentication type, transport
                            protocol, token status, and creation date. Click any server to view details and manage settings.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Adding Servers</Typography>
                        <Typography variant="body1" paragraph>
                            Click "Add MCP Server" to configure a new server.
                        </Typography>
                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Managing Servers</Typography>
                        <Typography variant="body1" paragraph>
                            Click a server in the table to access its detail page.
                        </Typography>
                    </>
                );
            case 'Edit MCP Server':
                return (
                    <>
                        <Typography variant="h6">MCP Server Details</Typography>
                        <Typography variant="body1" paragraph>
                            Configuration details for the selected MCP server.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Server Information</Typography>
                        <Typography variant="body1" paragraph>
                            View server UUID, name, URL, authentication type, transport protocol, static token
                            status, and timestamps. For OAuth Discovery servers, connection status is shown.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Actions</Typography>
                        <Typography variant="subtitle2">Connect/Disconnect</Typography>
                        <Typography variant="body1" paragraph>
                            Available for OAuth Discovery servers. Click Connect to authenticate in a new window.
                            Click Disconnect to terminate the connection.
                        </Typography>
                        <Typography variant="subtitle2">Load Tools</Typography>
                        <Typography variant="body1" paragraph>
                            Activate this server's tools for your upstream AI assistant.
                        </Typography>
                        <Typography variant="subtitle2">Unload Tools</Typography>
                        <Typography variant="body1" paragraph>
                            Deactivate this server's tools.
                        </Typography>
                        <Typography variant="subtitle2">Delete</Typography>
                        <Typography variant="body1" paragraph>
                            Permanently remove this server. Requires confirmation. Cannot be undone.
                        </Typography>
                    </>
                );
            case 'Add MCP Server':
                return (
                    <>
                        <Typography variant="h6">Add MCP Server</Typography>
                        <Typography variant="body1" paragraph>
                            Configure a new MCP server to extend your AI assistant's capabilities.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Configuration</Typography>
                        <Typography variant="subtitle2">Name</Typography>
                        <Typography variant="body1" paragraph>
                            Friendly name to identify the server.
                        </Typography>
                        <Typography variant="subtitle2">URL</Typography>
                        <Typography variant="body1" paragraph>
                            Full endpoint URL (e.g., https://example.com/mcp).
                        </Typography>
                        <Typography variant="subtitle2">Authentication Type</Typography>
                        <Typography variant="body1" paragraph>
                            <strong>None:</strong> No authentication required.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            <strong>OAuth Discovery:</strong> Authenticate via OAuth provider after adding.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            <strong>Static Header:</strong> Requires static token in field below.
                        </Typography>
                        <Typography variant="subtitle2">Transport Type</Typography>
                        <Typography variant="body1" paragraph>
                            Communication protocol. Currently supports Streaming HTTP only.
                        </Typography>
                        <Typography variant="subtitle2">Static Token</Typography>
                        <Typography variant="body1" paragraph>
                            Required for Static Header authentication. Token stored securely.
                        </Typography>

                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Saving</Typography>
                        <Typography variant="body1" paragraph>
                            Click "Add Server" to save. Click "Cancel" to return without saving.
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
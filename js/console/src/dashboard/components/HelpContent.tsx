import * as React from 'react';
import Typography from '@mui/material/Typography';
import {Fragment} from "react";

function HelpContent({selectedMenuItem}: { selectedMenuItem: string[] }) {
    const getHelpContent = () => {
        switch (selectedMenuItem.at(0)) {
            case 'Endpoints':
                return (
                    <>
                        <Typography variant="h6">Endpoints</Typography>
                        <Typography variant="body1" paragraph>
                            An Endpoint corresponds to a device/computer running under your account on Portola Network.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            Install the Portola App on your MacOS device. The device will show up here as an Endpoint.
                        </Typography>
                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Actions</Typography>
                        <Typography variant="subtitle2">Caffeinate</Typography>
                        <Typography variant="body1" paragraph>
                            Keep your Mac awake by clicking the Caffeinate button. If the device is currently offline,
                            it will be Caffeinated when it comes online periodically per MacOs system settings. To prevent
                            battery depletion, a device cannot be caffeinated when on battery power.
                        </Typography>
                        <Typography variant="subtitle2">Create Port Forward - Private</Typography>
                        <Typography variant="body1" paragraph>
                            To create a TCP Port Forward between two Endpoints on your account, click the
                            Create Port Forward button. You will be prompted to enter Server and Client ports. The tunnel
                            will be active immediately if the endpoints are online.
                        </Typography>
                        <Typography variant="subtitle2">Create Export</Typography>
                        <Typography variant="body1" paragraph>
                            To create a TCP Port Forward between your Endpoint and a remote Endpoint on another account,
                            you can create an Export. You will be prompted to enter Server ports and remote users' email.
                            The remote user will receive an email to accept the Export and pick Client side of the tunnel
                            using 'Create Port-Forward From Export'.
                        </Typography>
                        <Typography variant="subtitle2">Create Port-forward - From Export</Typography>
                        <Typography variant="body1" paragraph>
                            To create a TCP Port Forward from an Export created by another user, click the 'Create Port-forward
                            From Export' button. You will be prompted to enter Client port. The tunnel will be active immediately
                            if the endpoints are online.
                        </Typography>
                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Endpoint Detail</Typography>
                        <Typography variant="body1" paragraph>
                            Clicking on an Endpoint row will display 'Endpoint details' for the selected Endpoint.
                        </Typography>
                        <Typography variant="subtitle2">Tab: Tunnels</Typography>
                        <Typography variant="body1" paragraph>
                            This section displays all the Port Forwards created on selected Endpoint and their status. You can delete
                            a Port Forward by clicking the 'Delete' button. The tunnel will be deleted immediately.
                        </Typography>
                        <Typography variant="subtitle2">Tab: Exports</Typography>
                        <Typography variant="body1" paragraph>
                            This section displays all the Exports created on selected Endpoint. You can delete an Export
                            by clicking the 'Delete' buttonee.
                        </Typography>
                    </>
                );
            case 'Tunnels':
                return (
                    <>
                        <Typography variant="h6">Port Forwards</Typography>
                        <Typography variant="body1" paragraph>
                            A Port Forward is a TCP tunnel connecting ports on two Endpoints on your account. This table
                            displays all the Port Forwards created on an Endpoint on your account. A Port Forward is active
                            when both Endpoints are online.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            A Port Forward can be deleted by clicking the 'Delete' button. The tunnel will be deleted immediately.
                        </Typography>
                    </>
                );
            case 'Exports':
                return (
                    <>
                        <Typography variant="h6">Exports</Typography>
                        <Typography variant="body1" paragraph>
                            This Exports table displays all the Exports created and shared with you by other Portola users. You can
                            create a Port Forward from an Export by clicking the 'Create Port-forward From Export' button in
                            the Endpoints section.
                        </Typography>
                        <Typography variant="body1" paragraph>
                            A Port Forward can be deleted by clicking the 'Delete' button. The tunnel will be deleted immediately.
                        </Typography>
                    </>
                );
            case 'Finder':
                return (
                    <>
                        <Typography variant="h6">Dual Pane File Manager</Typography>
                        <Typography variant="body1" paragraph>
                            The Dual Pane File Manager allows you to view and manage filesystems from two different machines side by side.
                            This feature is designed to simplify file transfers and basic file system actions between two endpoints.
                        </Typography>
                        <Typography variant="subtitle1" sx={{ mb: 2 }}>Key Features</Typography>
                        <Typography variant="subtitle2">Side-by-Side View</Typography>
                        <Typography variant="body1" paragraph>
                            Each pane represents the filesystem of a different machine. You can navigate through directories, view files,
                            and perform actions independently in each pane.
                        </Typography>
                        <Typography variant="subtitle2">Drag and Drop File Transfers</Typography>
                        <Typography variant="body1" paragraph>
                            Easily transfer files between the two machines by dragging and dropping files or folders from one pane to the other.
                            The system ensures that files are copied to the correct location.
                        </Typography>
                        <Typography variant="subtitle2">Basic File System Actions</Typography>
                        <Typography variant="body1" paragraph>
                            Perform basic file system actions such as creating directories, renaming files, and refreshing the view.
                            These actions are available for both panes and can be performed independently.
                        </Typography>
                        <Typography variant="subtitle2">Quick Filters</Typography>
                        <Typography variant="body1" paragraph>
                            Use the quick filter feature to search and filter files within each pane. This helps you locate files quickly
                            without manually navigating through directories.
                        </Typography>
                        <Typography variant="subtitle2">Endpoint Management</Typography>
                        <Typography variant="body1" paragraph>
                            Select and manage endpoints for each pane. You can switch between different machines and configure their settings
                            as needed.
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
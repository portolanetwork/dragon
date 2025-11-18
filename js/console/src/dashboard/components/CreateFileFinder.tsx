import React, { useState } from 'react';
import { Box, Button, Typography } from '@mui/material';
import SelectEndpointGrid from './SelectEndpointGrid';
import { EndpointRow } from './types/EndpointRow';
import { EndpointFilepathRow } from './types/EndpointFilepathRow';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";

interface CreateFileFinderProps {
    endpointRows: EndpointRow[];
    onDialogClose: (leftEndpointFilepath: EndpointFilepathRow | null, rightEndpointFilepath: EndpointFilepathRow | null) => void;
}

export default function CreateFileFinder({ endpointRows, onDialogClose }: CreateFileFinderProps) {
    const [user, loading, error] = useAuthState(auth);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const [leftEndpointFilepath, setLeftEndpointFilepath] = useState<EndpointFilepathRow | null>(null);
    const [rightEndpointFilepath, setRightEndpointFilepath] = useState<EndpointFilepathRow | null>(null);

    const createFileFinder = async (leftEndpointFilepath: EndpointFilepathRow, rightEndpointFilepath: EndpointFilepathRow) => {
        console.log(`Creating file finder for serverEndpoint ${leftEndpointFilepath} and ${rightEndpointFilepath}`);

        await SpyderProxy.getInstance()
            .createFileFinder(user, leftEndpointFilepath, rightEndpointFilepath)
            .then(() => {
                console.log(`Created tunnel for serverEndpoint ${leftEndpointFilepath.endpointId} and ${rightEndpointFilepath.endpointId}`);
            })
            .catch((error) => {
                console.log(`Error creating tunnel for serverEndpoint ${leftEndpointFilepath.endpointId} and ${rightEndpointFilepath.endpointId}: ${error.message}`);
            });
    };


    const handleLeftEndpointSelected = (endpoint: EndpointRow) => {
        console.log("Left endpoint selected: ", endpoint);

        setLeftEndpointFilepath({
            endpointId: endpoint.id,
            hostname: endpoint.hostname,
            homedir: endpoint.homedir,
            endpointIsOnline: endpoint.isOnline,
            path: endpoint.homedir
        });
    };

    const handleRightEndpointSelected = (endpoint: EndpointRow) => {
        console.log("Right endpoint selected: ", endpoint);

        setRightEndpointFilepath({
            endpointId: endpoint.id,
            hostname: endpoint.hostname,
            homedir: endpoint.homedir,
            endpointIsOnline: endpoint.isOnline,
            path: endpoint.homedir
        });
    };

    const handleCreate = async () => {
        if (leftEndpointFilepath && rightEndpointFilepath) {
            // Call createFileFinder only if both filepaths are not null
            await createFileFinder(leftEndpointFilepath, rightEndpointFilepath);
            await onDialogClose(leftEndpointFilepath, rightEndpointFilepath);
        } else {
            console.error("Both endpoints must be selected before creating a file finder.");
        }
    };

    const handleCancel = () => {
        onDialogClose(null, null);
    };

    return (
        <Box>
            <Typography variant="h6" gutterBottom>
                Select Endpoints
            </Typography>
            <Box mb={2}>
                <Typography variant="subtitle1">Left</Typography>
                <SelectEndpointGrid onEndpointSelected={handleLeftEndpointSelected} />
            </Box>
            <Box mb={2}>
                <Typography variant="subtitle1">Right</Typography>
                <SelectEndpointGrid onEndpointSelected={handleRightEndpointSelected} />
            </Box>
            <Box display="flex" justifyContent="flex-end" mt={2}>
                <Button onClick={handleCancel} color="secondary">
                    Cancel
                </Button>
                <Button
                    onClick={handleCreate}
                    color="primary"
                    //disabled={!leftEndpointFilepath || !rightEndpointFilepath}
                    sx={{ ml: 2 }}
                >
                    Create
                </Button>
            </Box>
        </Box>
    );
}
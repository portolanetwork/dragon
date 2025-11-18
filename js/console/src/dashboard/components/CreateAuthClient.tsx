import React, { useState } from 'react';
import { Box, Button, Typography, Paper, Stack, FormControl, InputLabel, Select, MenuItem } from '@mui/material';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import { useAuthState } from "react-firebase-hooks/auth";
import { auth } from "../../firebase";
import UUIDDisplay from './UUIDDisplay';
import { AuthClientRow } from "./types/AuthClientRow";

interface CreateAuthClientProps {
    onDialogClose: () => void;
    endpointRows: Array<{ id: string; hostname: string }>;
}

export default function CreateAuthClient({ onDialogClose, endpointRows }: CreateAuthClientProps) {
    const [user] = useAuthState(auth);
    const [endpointId, setEndpointId] = useState<string>(endpointRows.length > 0 ? endpointRows[0].id : '*');
    const [authClientRow, setAuthClientRow] = useState<AuthClientRow | null>(null);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const handleCancel = () => {
        onDialogClose();
    };

    const handleCreate = async () => {
        let thisEndpointId = '';

        if (!endpointId) {
            console.error("No endpointId entered");
            return;
        } else if (endpointId.trim() === '*') {
            thisEndpointId = '';
        } else {
            thisEndpointId = endpointId.trim();
        }
        console.log("Creating Auth Client for endpointId: ", thisEndpointId);

        await SpyderProxy.getInstance()
            .createClient(user, thisEndpointId)
            .then((authClientRow) => {
                console.log(`Created Auth Client for endpointId ${endpointId}`);
                setAuthClientRow(authClientRow);
            })
            .catch((error) => {
                console.error(`Error creating Auth Client for endpointId ${endpointId}: ${error.message}`);
            });
    };

    return (
        <Box>
            {!authClientRow && (
                <>
                    <Box mb={2}>
                        <Typography variant="h6" gutterBottom>
                            Select Endpoint
                        </Typography>
                        <FormControl fullWidth>
                            <InputLabel id="endpoint-select-label">Endpoint</InputLabel>
                            <Select
                                labelId="endpoint-select-label"
                                value={endpointId}
                                label="Endpoint"
                                onChange={e => setEndpointId(e.target.value)}
                            >
                                <MenuItem value="*">*</MenuItem>
                                {endpointRows.map((ep) => (
                                    <MenuItem key={ep.id} value={ep.id}>{ep.hostname} ({ep.id})</MenuItem>
                                ))}
                            </Select>
                        </FormControl>
                        <Typography variant="caption" color="text.secondary">
                            Use "*" to create an auth client not bound to a specific endpoint.
                        </Typography>
                    </Box>
                    <Box display="flex" justifyContent="flex-end" mt={2}>
                        <Button onClick={handleCancel} color="secondary">
                            Cancel
                        </Button>
                        <Button
                            onClick={handleCreate}
                            color="primary"
                            sx={{ ml: 2 }}
                        >
                            Create
                        </Button>
                    </Box>
                </>
            )}
            {authClientRow && (
                <Paper elevation={3} sx={{ p: 3, mt: 2, maxWidth: 580, mx: 'auto' }}>
                    <Stack spacing={2}>
                        <Typography variant="h6" color="primary">
                            Auth Client Created
                        </Typography>
                        <Typography variant="body2" color="text.secondary">
                            Copy your client secret and keep it safe. It will not be shown again.
                        </Typography>
                        <Stack spacing={1}>
                            <Typography component="span">
                                <UUIDDisplay label="Endpoint ID:" uuid={authClientRow.endpointId || '*'} truncate={-1} />
                            </Typography>
                            <Typography component="span">
                                <UUIDDisplay label= "Client Tenant ID:" uuid={authClientRow.clientTenantId} truncate={-1} />
                            </Typography>
                            <Typography component="span">
                                <UUIDDisplay label="Client ID:" uuid={authClientRow.clientId} truncate={-1} />
                            </Typography>
                            <Typography component="span">
                                <UUIDDisplay label="Client Secret:" uuid={authClientRow.clientSecret} truncate={-1} />
                            </Typography>
                        </Stack>
                        <Button
                            onClick={() => onDialogClose()}
                            variant="outlined"
                            color="primary"
                            sx={{ alignSelf: 'flex-end', mt: 2 }}
                        >
                            Dismiss
                        </Button>
                    </Stack>
                </Paper>
            )}
        </Box>
    );
}
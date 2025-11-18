import * as React from 'react';
import { useState } from 'react';
import { Dialog, DialogContent, DialogTitle, Box, Button } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";

import CreateAuthClient from './CreateAuthClient';

export default function CreateAuthClientDialog({ onDialogClose, endpointRows }: { onDialogClose: () => void, endpointRows: Array<{ id: string; hostname: string }> }) {
    const [user, loading, error] = useAuthState(auth);
    const [open, setOpen] = useState(false);

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }

    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
        onDialogClose();
    };

    return (
        <Box>
            <Button
                variant="text"
                color="primary"
                onClick={handleClickOpen}
                sx={{ padding: '10px 20px', borderRadius: '0' }}
                startIcon={<AddIcon />}
            >
                Create Auth Client
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{ '& .MuiDialog-paper': { width: '650px' } }}>
                <DialogTitle>Create Auth Client</DialogTitle>
                <DialogContent>
                    <CreateAuthClient onDialogClose={handleClose} endpointRows={endpointRows} />
                </DialogContent>
            </Dialog>
        </Box>
    );
}
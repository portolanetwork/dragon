import React from 'react';
import { Box, Tooltip, Button, Typography } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';

interface UUIDDisplayProps {
    uuid: string;
    truncate?: number; // Optional prop for custom truncation length
    label?: string;    // Optional label
}

const truncateUUID = (uuid: string, length: number) =>
    uuid.length > length ? `${uuid.substring(0, length)}...` : uuid;

const copyToClipboard = (uuid: string) => {
    navigator.clipboard.writeText(uuid);
};

const UUIDDisplay: React.FC<UUIDDisplayProps> = ({ uuid, truncate, label }) => {
    let displayValue: string;
    if (truncate === -1) {
        displayValue = uuid;
    } else {
        displayValue = truncate ? truncateUUID(uuid, truncate) : truncateUUID(uuid, 8);
    }

    return (
        <Box display="flex" alignItems="center">
            {label && (
                <Typography variant="subtitle2" color="text.secondary" sx={{ mr: 1, fontWeight: 600 }}>
                    {label}
                </Typography>
            )}
            <Tooltip title={uuid}>
                <span>{displayValue}</span>
            </Tooltip>
            <Button
                onClick={(event) => {
                    event.stopPropagation();
                    copyToClipboard(uuid);
                }}
                size="small"
                sx={{ ml: 1, width: 32, height: 32, minWidth: 0, padding: 0 }}
            >
                <ContentCopyIcon fontSize="small" />
            </Button>
        </Box>
    );
};

export default UUIDDisplay;
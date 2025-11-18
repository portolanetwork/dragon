// src/components/MessagePage.tsx
import * as React from 'react';
import { useLocation } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';

const MessagePage: React.FC = () => {
    const location = useLocation();
    const queryParams = new URLSearchParams(location.search);
    const message = queryParams.get('message') || 'Default message';

    return (
        <Box mt={4} display="flex" justifyContent="center" alignItems="center" height="100vh">
            <Card sx={{ width: '600px' }}>
                <CardContent>
                    <Typography variant="h5" component="div" gutterBottom>
                        Message
                    </Typography>
                    <Typography variant="body1">
                        {message}
                    </Typography>
                </CardContent>
            </Card>
        </Box>
    );
};

export default MessagePage;
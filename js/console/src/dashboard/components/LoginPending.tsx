import * as React from 'react';
import { useLocation } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import { styled } from '@mui/material/styles';
import AppTheme from '../../shared-theme/AppTheme';
import CssBaseline from '@mui/material/CssBaseline';

const LoginPendingContainer = styled(Box)(({ theme }) => ({
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    height: 'calc((1 - var(--template-frame-height, 0)) * 100dvh)',
    minHeight: '100%',
    padding: theme.spacing(2),
    [theme.breakpoints.up('sm')]: {
        padding: theme.spacing(4),
    },
    '&::before': {
        content: '""',
        display: 'block',
        position: 'absolute',
        zIndex: -1,
        inset: 0,
        backgroundImage:
            'radial-gradient(ellipse at 50% 50%, hsl(210, 100%, 97%), hsl(0, 0%, 100%))',
        backgroundRepeat: 'no-repeat',
        ...theme.applyStyles('dark', {
            backgroundImage:
                'radial-gradient(at 50% 50%, hsla(210, 100%, 16%, 0.5), hsl(220, 30%, 5%))',
        }),
    },
}));

const StyledCard = styled(Card)(({ theme }) => ({
    width: '100%',
    padding: theme.spacing(6),
    margin: 'auto',
    [theme.breakpoints.up('sm')]: {
        maxWidth: '600px',
    },
    boxShadow:
        'hsla(220, 30%, 5%, 0.05) 0px 5px 15px 0px, hsla(220, 25%, 10%, 0.05) 0px 15px 35px -5px',
    ...theme.applyStyles('dark', {
        boxShadow:
            'hsla(220, 30%, 5%, 0.5) 0px 5px 15px 0px, hsla(220, 25%, 10%, 0.08) 0px 15px 35px -5px',
    }),
}));

const LoginPending: React.FC = () => {
    const location = useLocation();
    const queryParams = new URLSearchParams(location.search);
    const email = queryParams.get('email');

    return (
        <AppTheme>
            <CssBaseline enableColorScheme />
            <LoginPendingContainer>
                <StyledCard variant="outlined">
                    <CardContent>
                        <Typography variant="h5" component="div" gutterBottom align="center">
                            Check Your Email
                        </Typography>
                        <Typography variant="body1" align="left">
                            We have sent a sign-in link to {email}. Please check your email and follow the instructions to complete the sign-in process.
                        </Typography>
                    </CardContent>
                </StyledCard>
            </LoginPendingContainer>
        </AppTheme>
    );
};

export default LoginPending;
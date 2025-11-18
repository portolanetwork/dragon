import * as React from 'react';
import { auth } from "../../firebase";
import {useEffect, useState} from 'react';
import {useAuthState} from 'react-firebase-hooks/auth';
import {Box, Chip, Divider, Typography, Card, CardContent, Tabs, Tab} from '@mui/material';
import SpyderProxy from '../../spyder_proxy/SpyderProxy';


const Subscription: React.FC = () => {
    const [user, loading, error] = useAuthState(auth);

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }

    const createStripeCheckoutSession = async () => {
        await SpyderProxy.getInstance()
            .createStripeCheckoutSession(user, 'pro')
            .then((sessionUrl) => {
                console.log(`Stripe Checkout Session created: ${sessionUrl}`);
                window.location.href = sessionUrl; // Redirect to Stripe Checkout
            }).catch((error) => {
                console.error(`Error creating Stripe Checkout Session: ${error.message}`);
            });
    }

    useEffect(() => {
        // Fetch subscription details or any other necessary data here

        createStripeCheckoutSession(); // Automatically create a checkout session on component mount
    }, []);

    return (
        <Box
            sx={{
                width: '100%',
                height: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                backgroundColor: 'background.paper',
            }}
        >
            <h1>Subscription Management</h1>
            <p>Manage your subscription details here.</p>
            {/* Add subscription management components here */}
        </Box>
    );

};

export default Subscription;
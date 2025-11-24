import * as React from 'react';
import * as ReactDOM from 'react-dom/client';
import { ThemeProvider } from '@mui/material/styles';
import { CssBaseline } from '@mui/material';
import theme from './theme';
import App from './dashboard/Dashboard';
import { createBrowserRouter, RouterProvider } from 'react-router';
import Auth0ProtectedRoute from './dashboard/components/Auth0ProtectedRoute';
import {Auth0Provider} from "@auth0/auth0-react";

const deploymentConfig = (window as any).deploymentConfig || {};
const domain = deploymentConfig.auth0Domain || import.meta.env.VITE_AUTH0_DOMAIN;
const clientId = deploymentConfig.auth0ClientId || import.meta.env.VITE_AUTH0_CLIENT_ID;

const router = createBrowserRouter([
    {
        path: '/',
        element: (
            <Auth0Provider
                domain={domain}
                clientId={clientId}
                authorizationParams={{
                    redirect_uri: window.location.origin,
                }}
            >
                <Auth0ProtectedRoute>
                    <App />
                </Auth0ProtectedRoute>
            </Auth0Provider>
        ),
    }
]);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
        { /* <App /> */}
        <RouterProvider router={router} />
    </ThemeProvider>
  </React.StrictMode>,
);

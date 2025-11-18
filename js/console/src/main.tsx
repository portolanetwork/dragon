import * as React from 'react';
import * as ReactDOM from 'react-dom/client';
import { ThemeProvider } from '@mui/material/styles';
import { CssBaseline } from '@mui/material';
import theme from './theme';
import App from './dashboard/Dashboard';
import { createBrowserRouter, RouterProvider } from 'react-router';
import Auth0ProtectedRoute from './dashboard/components/Auth0ProtectedRoute';
import SignInContainer from './sign-in/SignIn';
import LoginPending from "./dashboard/components/LoginPending";
import MessagePage from "./dashboard/components/MessagePage";
import LoginDone from './dashboard/components/LoginDone';
import {Auth0Provider} from "@auth0/auth0-react";

const router = createBrowserRouter([
    {
        path: '/',
        element: (
            <Auth0Provider
                domain="portola-dev.us.auth0.com"
                clientId="sfxhXr2jMA0arQ0C3kAGatI1z0k4j8rt"
                authorizationParams={{
                    //audience: "https://dragonpi.app/api",
                    redirect_uri: 'http://localhost:5173',//window.location.origin,
                    //scope: 'openid profile email',
                }}
            >
                <Auth0ProtectedRoute>
                    <App />
                </Auth0ProtectedRoute>
            </Auth0Provider>
        ),
    },
    {
        path: '/sign-in',
        element: <SignInContainer />,
    },
    {
        path: '/proceed',
        element: <LoginDone />,
    },
    {
        path: '/pending',
        element: <LoginPending />,
    },
    {
        path: '/message',
        element: <MessagePage />,
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

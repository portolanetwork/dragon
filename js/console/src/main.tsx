import * as React from 'react';
import * as ReactDOM from 'react-dom/client';
import { ThemeProvider } from '@mui/material/styles';
import { CssBaseline } from '@mui/material';
import theme from './theme';
import App from './dashboard/Dashboard';
import { createBrowserRouter, RouterProvider } from 'react-router';
import AuthRoute from './dashboard/components/AuthRoute';
import SignInContainer from './sign-in/SignIn';
import LoginPending from "./dashboard/components/LoginPending";
import MessagePage from "./dashboard/components/MessagePage";
import LoginDone from './dashboard/components/LoginDone';
import {Auth0Provider} from "@auth0/auth0-react";

const router = createBrowserRouter([
    {
        path: '/',
        element: (
            <Auth0Provider>
                <App />
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

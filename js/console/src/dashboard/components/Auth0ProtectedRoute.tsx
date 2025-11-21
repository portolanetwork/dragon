import React, { ReactElement } from 'react';
import { useAuth0 } from '@auth0/auth0-react';

const Auth0ProtectedRoute = ({ children }: { children: ReactElement }) => {
    const { isAuthenticated, isLoading, loginWithRedirect } = useAuth0();

    React.useEffect(() => {
        if (!isLoading && !isAuthenticated) {
            loginWithRedirect();
        }
    }, [isLoading, isAuthenticated, loginWithRedirect]);

    if (isLoading) {
        return <div>Loading...</div>;
    }

    return isAuthenticated ? children : null;
};

export default Auth0ProtectedRoute;

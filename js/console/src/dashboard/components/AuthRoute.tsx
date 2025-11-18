import React, { ReactElement, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthState } from "react-firebase-hooks/auth";

import { getAuth, isSignInWithEmailLink, signInWithEmailLink } from "firebase/auth";
//import { firebaseAuth as auth } from '../../firebase/firebaseConfig';
import { auth } from "../../firebase";


import {useNavigate} from "react-router";

const AuthRoute = ({ children }: { children: ReactElement }) => {
    const [user, loading, error] = useAuthState(auth);
    const [isSigningIn, setIsSigningIn] = useState(true);
    const navigate = useNavigate();

    useEffect(() => {
        const signIn = async () => {
            if (isSignInWithEmailLink(auth, window.location.href)) {
                const email = window.localStorage.getItem('emailForSignIn');

                console.log('------------ Email for sign in: ', email);

                if (!email) {
                    //window.alert('Email is missing. Please sign in again.');

                    //navigate('/error');
                    navigate("/message?message=Email link is invalid.");

                    setIsSigningIn(false);
                } else {

                    try {
                        console.log('------------ Signing in with email link...');
                        await signInWithEmailLink(auth, email, window.location.href);
                        window.localStorage.removeItem('emailForSignIn');

                        console.log('------------ User email: ', auth.currentUser);
                        console.log('------------ User: ', user);

                        if (auth.currentUser?.email != email) {
                            // navigate to error page
                            navigate("/message?message=User email does not match. Please sign-in again.");
                        }
                    } catch (error) {
                        console.log('Error signing in with email link: ', error);
                    } finally {
                        setIsSigningIn(false);
                    }
                }
            } else {
                setIsSigningIn(false);
            }
        };

        signIn();
    }, [auth]);

    if (loading || isSigningIn) {
        return <div>Loading...</div>;
    }

    if (error) {
        return <div>Error: {error.message}</div>;
    }

    return user ? React.cloneElement(children) : <Navigate to="/sign-in" />;
};

export default AuthRoute;
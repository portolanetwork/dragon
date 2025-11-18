import * as React from 'react';
import {useEffect} from "react";
import {useState} from "react";

import {useNavigate} from 'react-router-dom';
import {useAuthState} from 'react-firebase-hooks/auth';

import {auth} from '../../firebase';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Avatar from '@mui/material/Avatar';
import {styled} from '@mui/material/styles';
import MuiCard from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import AppTheme from '../../shared-theme/AppTheme';
import CssBaseline from "@mui/material/CssBaseline";
import queryString from "query-string";
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
//import config from "../../config"

const Card = styled(MuiCard)(({theme}) => ({
    display: 'flex',
    flexDirection: 'column',
    alignSelf: 'center',
    width: '100%',
    padding: theme.spacing(6),
    gap: theme.spacing(),
    margin: 'auto',
    [theme.breakpoints.up('sm')]: {
        maxWidth: '410px',
    },
    boxShadow:
        'hsla(220, 30%, 5%, 0.05) 0px 5px 15px 0px, hsla(220, 25%, 10%, 0.05) 0px 15px 35px -5px',
    ...theme.applyStyles('dark', {
        boxShadow:
            'hsla(220, 30%, 5%, 0.5) 0px 5px 15px 0px, hsla(220, 25%, 10%, 0.08) 0px 15px 35px -5px',
    }),
}));

const LoginDoneContainer = styled(Stack)(({theme}) => ({
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

//const LoginDone : React.FC = () => {
export default function LoginDone(props: { disableCustomTheme?: boolean }) {
    const [response, setResponse] = useState(null);
    const [user, loading, error] = useAuthState(auth);
    const navigate = useNavigate();
    const {state, code_challenge} = queryString.parse(window.location.search);

    const handleProceedToConsole = () => {
        navigate('/');
    };

    /*
    useEffect(() => {


        if (user != null) {
            //console.log("--------------------- User: ", user);
            //console.log("--------------------- State: ", state);
            //console.log("--------------------- Port: ", port);

            const redemptionCode = SpyderProxy.getInstance().putAuthData(user, code_challenge).catch((error) => {
                console.error("Error putting auth data: ", error);
                //setLoading(false);
            });

            navigate("portola://app_auth/redeem?code=" + redemptionCode + "&state=" + (state !== null ? state.toString() : ""));


            user.getIdTokenResult().then((idTokenResult) => {
                console.log("ID Token Result: ", idTokenResult);

                const url = new URL("http://localhost:" + port + "/auth/callback");

                url.searchParams.append("access_token", idTokenResult.token);
                url.searchParams.append("refresh_token", user.refreshToken);
                url.searchParams.append("state", state !== null ? state.toString() : "");

                fetch(url, {
                    method: "GET",
                    headers: {
                        "Content-Type": "application/json"
                    }
                })
                    .then((res) => res.json())
                    .then((data) => {
                        console.log("Response: ", data);
                        //setLoading(false);
                        setResponse(data);
                    })
                    .catch((error) => {
                        //setLoading(false);
                        console.log(error)
                    });
            });


            return () => {
                console.log("Cleanup");
            };
        }
    }, [user, loading, error]);
     */

    useEffect(() => {
        const completeLoginFlow = async ()  => {

            if (user != null) {
                const codeChallenge  = Array.isArray(code_challenge)
                    ? code_challenge[0]
                    : code_challenge || "";

                const redemptionCode = await SpyderProxy.getInstance()
                    .putAuthData(user, codeChallenge || "")
                    .then((redemptionCode) => {
                        console.log("Redemption code: ", redemptionCode.code);
                        return redemptionCode.code;
                    }).catch((error) => {
                        console.error("Error putting auth data: ", error);
                        //setLoading(false);
                    });

                //navigate("portola://app_auth/redeem?code=" + redemptionCode + "&state=" + (state !== null ? state.toString() : ""));

                console.log("hosting: ", window.location.hostname);

                let urlScheme = "portolastaging";

                if (window.location.hostname === 'console.portola.app') {
                    urlScheme = "portola";
                }

                // Use window.location.href to open the custom URL scheme
                //window.location.href = `portola://app_auth/redeem?code=${redemptionCode}&state=${state !== null ? state.toString() : ""}`;
                window.location.href = `${urlScheme}://app_auth/redeem?code=${redemptionCode}&state=${state !== null ? state.toString() : ""}`;
            }
        };


        completeLoginFlow();

    }, [user, code_challenge, state]);


    if (!user) {
        console.error("User not logged in");
        return;
    }


    if (loading) {
        return (
            <LoginDoneContainer direction="column" justifyContent="center">
                <Typography variant="h6" align="center">
                    Loading...
                </Typography>
            </LoginDoneContainer>
        );
    }

    return (
        <AppTheme {...props}>
            <CssBaseline enableColorScheme/>
            <LoginDoneContainer direction="column" justifyContent="center">
                <Card variant="outlined">
                    <Avatar
                        alt={user.displayName || 'User'}
                        src={user.photoURL || ''}
                        sx={{width: 80, height: 80, margin: '0 auto', marginBottom: 2}}
                    />
                    <Typography variant="h6" align="center">
                        Welcome, {user.displayName || 'User'}!
                    </Typography>
                    <Typography variant="body2" align="center" color="textSecondary">
                        {user.email || 'Email not available'}
                    </Typography>
                    <Button
                        fullWidth
                        variant="contained"
                        onClick={handleProceedToConsole}
                        sx={{
                            marginTop: 2,
                            borderRadius: '5px',
                            fontWeight: 'bold',
                        }}
                    >
                        Proceed to Console
                    </Button>
                </Card>
            </LoginDoneContainer>
        </AppTheme>
    );
};

//export default LoginDone;
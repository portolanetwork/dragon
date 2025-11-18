import * as React from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import CssBaseline from '@mui/material/CssBaseline';
import FormControlLabel from '@mui/material/FormControlLabel';
import Divider from '@mui/material/Divider';
import FormLabel from '@mui/material/FormLabel';
import FormControl from '@mui/material/FormControl';
import Link from '@mui/material/Link';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import Stack from '@mui/material/Stack';
import MuiCard from '@mui/material/Card';
import {styled, useTheme} from '@mui/material/styles';
import ForgotPassword from './components/ForgotPassword';
import AppTheme from '../shared-theme/AppTheme';
import ColorModeSelect from '../shared-theme/ColorModeSelect';
import {GoogleIcon, SitemarkIcon, GithubIcon} from './components/CustomIcons';
import Alert from '@mui/material/Alert';

//import logo from '../assets/portolanetwork_logo_lighter.png';
import logoDarker from '../assets/portolanetwork_logo_darker.png';
import logoLighter from '../assets/portolanetwork_logo_lighter.png';

//import portolaAppIcon from '../assets/portola_icon.png';
//import portolaAppLogo from '../assets/portola_logo_white.png';


import queryString from "query-string";

import {
    createUserWithEmailAndPassword,
    signInWithEmailAndPassword,
    onAuthStateChanged,
    sendSignInLinkToEmail,
    signOut,
    GoogleAuthProvider,
    GithubAuthProvider,
    signInWithPopup,
} from "firebase/auth";

//import {firebaseAuth as auth} from '../firebase/firebaseConfig';
import { auth } from "../firebase";

import {useNavigate} from "react-router";
import {useEffect} from "react";


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

const SignInContainer = styled(Stack)(({theme}) => ({
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

export default function SignIn(props: { disableCustomTheme?: boolean }) {
    const theme = useTheme();
    const isDarkMode = theme.palette.mode === 'dark';
    const [emailError, setEmailError] = React.useState(false);
    const [emailErrorMessage, setEmailErrorMessage] = React.useState('');
    //const [open, setOpen] = React.useState(false);
    const navigate = useNavigate();
    //const {state, port} = queryString.parse(location.search);
    const {state, code_challenge} = queryString.parse(location.search);
    const [errorMessage, setErrorMessage] = React.useState('');


    const [postLoginUrl, setPostLoginUrl] = React.useState(`/`);

    //console.log("state: ", state);
    //console.log("port: ", code_challenge);

    useEffect(() => {
        console.log("state: ", state);
        console.log("code_challenge: ", code_challenge);

        if (state && code_challenge) {
            //setPortLoginUrl(`${window.location.protocol}//${window.location.host}/?state=${state}&port=${port}`);
            //console.log("Setting postLoginUrl");
            setPostLoginUrl("/proceed?state=" + state + "&code_challenge=" + code_challenge);
        }
    }, [state, code_challenge]);



    /*
    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
    };

     */


    const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault(); // Prevent default form submission

        console.log("Submit");

        if (emailError) {
            console.log("Email error");
            event.preventDefault();
            return;
        }
        const data = new FormData(event.currentTarget);
        console.log({
            email: data.get('email'),
            password: data.get('password'),
        });

        const email = data.get('email') as string;

        await emailLinkSignIn(email, Math.random().toString(36).substring(7));
    };

    const emailLinkSignIn = async (email: string, state: string) => {
        //const state = Math.random().toString(36).substring(7);
        const baseUrl = `${window.location.protocol}//${window.location.host}`;

        console.log("Email: ", email);
        console.log("State: ", state);
        console.log("Base URL: ", baseUrl);

        const actionCodeSettings = {
            // URL you want to redirect back to. The domain (www.example.com) for this
            // URL must be in the authorized domains list in the Firebase Console.
            //url: baseUrl + "/home?state=" + state,
            //url: baseUrl + "/?state=" + state + "&type=email-link",
            //url: baseUrl,

            url: baseUrl + postLoginUrl, // Redirect to the post-login URL after email verification
            // This must be true.
            handleCodeInApp: true,
            ttl: 60 * 5, // 5 min
            //linkDomain: 'console.staging.portolanetwork.com',
            //lingDomain: 'localhost',
        };

        await sendSignInLinkToEmail(auth, email, actionCodeSettings)
            .then(() => {
                // Save the state to the local storage
                //window.localStorage.setItem('state', state);
                window.localStorage.setItem('emailForSignIn', email);

                console.log("Email sent");

                navigate(`/pending?state=${state}&email=${encodeURIComponent(email)}`);
            }).catch((error) => {
                console.error("Email link signin: ", error);
            });
    }

    const googleSignIn = () => {
        const provider = new GoogleAuthProvider();

        return signInWithPopup(auth, provider)
            .then((result) => {
                //navigate('/');
                navigate(postLoginUrl);
            }).catch((error) => {
                console.error("Google singin: ", error);
            });
    }

    const githubSignIn = () => {
        const provider = new GithubAuthProvider();

        return signInWithPopup(auth, provider)
            .then((result) => {
                //navigate('/');
                navigate(postLoginUrl);
            }).catch((error) => {
                console.error("Github singin: ", error);
                setErrorMessage('GitHub sign-in failed. Please try again or use another login method.');
            });
    }

    const validateInputs = () => {
        console.log("Validate inputs");
        const email = document.getElementById('email') as HTMLInputElement;
        //const password = document.getElementById('password') as HTMLInputElement;

        let isValid = true;

        if (!email.value || !/\S+@\S+\.\S+/.test(email.value)) {
            setEmailError(true);
            setEmailErrorMessage('Please enter a valid email address.');
            isValid = false;
        } else {
            setEmailError(false);
            setEmailErrorMessage('');
        }

        return isValid;
    };

    return (
        <AppTheme {...props}>
            <CssBaseline enableColorScheme/>
            <SignInContainer direction="column" justifyContent="space-between">
                { /* <ColorModeSelect sx={{ position: 'fixed', top: '1rem', right: '1rem' }} />  */}
                <Card variant="outlined">

                    <img src={logoDarker} alt="Portola Network, Inc" className="logo logo-padding"/>
                    { /*}
                    <div>
                        <img src={portolaAppIcon} id="banner-logo" style={{ height: '40px' }} />
                        <img src={portolaAppLogo} id="banner-logo" style={{ height: '40px' }} />
                    </div>
                    */}

                    <Box sx={{p: 1}}/>

                    {errorMessage && (
                        <Alert severity="error" sx={{ marginBottom: 2 }}>
                            {errorMessage}
                        </Alert>
                    )}

                    <Box sx={{p: 1}}/>

                    <Box
                        component="form"
                        onSubmit={handleSubmit}
                        noValidate
                        sx={{
                            display: 'flex',
                            flexDirection: 'column',
                            width: '100%',
                            gap: 2,
                        }}
                    >
                        <FormControl>

                            <TextField
                                error={emailError}
                                helperText={emailErrorMessage}
                                id="email"
                                type="email"
                                name="email"
                                placeholder="You email address"
                                autoComplete="email"
                                autoFocus
                                required
                                fullWidth
                                variant="outlined"
                                color={emailError ? 'error' : 'primary'}
                            />
                        </FormControl>
                        { /*<ForgotPassword open={open} handleClose={handleClose}/> */}
                        <Button
                            type="submit"
                            fullWidth
                            variant="contained"
                            onClick={validateInputs}
                        >
                            Continue
                        </Button>

                    </Box>
                    <Box sx={{p: 1}}/>
                    <Divider>OR</Divider>
                    <Box sx={{p: 1}}/>
                    <Box sx={{display: 'flex', flexDirection: 'column', gap: 2}}>
                        <Button
                            fullWidth
                            variant="outlined"
                            style={{
                                borderRadius: "5px",
                                fontSize: "14px",
                                fontWeight: "bold",
                                display: "flex",
                                justifyContent: "center",
                            }}

                            //onClick={() => alert('Sign in with Google')}
                            onClick={googleSignIn}
                            startIcon={<GoogleIcon/>}
                        >
                            Continue with Google
                        </Button>
                        <Button
                            fullWidth
                            variant="outlined"
                            style={{
                                borderRadius: "5px",
                                fontSize: "14px",
                                fontWeight: "bold",
                                display: "flex",
                                justifyContent: "center",
                            }}
                            //onClick={() => alert('Sign in with Github')}
                            onClick={githubSignIn}
                            startIcon={<GithubIcon/>}
                        >
                            Continue with Github
                        </Button>
                        <Typography component="h1" variant="caption" sx={{textAlign: 'center'}}>
                            By continuing, you agree to Portola Network's {' '}
                            <br/>
                            <Link
                                href="https://portolanetwork.notion.site/Terms-of-Service-1655caeefb15803dae11d8e190ece2e9"
                                variant="caption"
                                sx={{alignSelf: 'center'}}
                            >
                                Terms of Service
                            </Link>
                        </Typography>

                    </Box>
                </Card>
            </SignInContainer>
        </AppTheme>
    );
}

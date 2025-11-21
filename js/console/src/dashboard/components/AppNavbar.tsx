import * as React from 'react';
import {styled} from '@mui/material/styles';
import AppBar from '@mui/material/AppBar';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import MuiToolbar from '@mui/material/Toolbar';
import {tabsClasses} from '@mui/material/Tabs';
import Typography from '@mui/material/Typography';
import MenuRoundedIcon from '@mui/icons-material/MenuRounded';
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded';
import portolaAppIcon from '../../assets/portola_icon.png';
import dragonAppDarkerLogo from '../../assets/dragon_logo_darker.png';


import { useTheme } from '@mui/material/styles';


const Toolbar = styled(MuiToolbar)({
    width: '100%',
    padding: '12px',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'start',
    justifyContent: 'center',
    gap: '12px',
    flexShrink: 0,
    [`& ${tabsClasses.flexContainer}`]: {
        gap: '8px',
        p: '8px',
        pb: 0,
    },
});

export default function AppNavbar() {
    const theme = useTheme();
    const isDarkMode = theme.palette.mode === 'dark';
    const [open, setOpen] = React.useState(false);

    const toggleDrawer = (newOpen: boolean) => () => {
        setOpen(newOpen);
    };

    const handleMenuItemClick = (menuItem: string) => {
        // Menu item click handler
    };

    // <AppBar position="fixed" sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}>

    return (
        <AppBar
            position="fixed"
            sx={{
                zIndex: (theme) => theme.zIndex.drawer + 1,

                //display: {xs: 'auto', md: 'none'},
                boxShadow: 0,
                bgcolor: 'background.paper',

                backgroundImage: 'none',
                borderBottom: '1px solid',
                borderColor: 'divider',
                top: 'var(--template-frame-height, 0px)',
            }}
        >
            {<Box
                    sx={{
                        width: '100%',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        p: 3,
                        //padding: '15px',
                        borderBottom: '1px solid',
                        borderColor: 'divider',
                        //backgroundColor: '#222222',
                    }}
                >
                    { /* <img src={logo} alt="Portola" style={{ height: '40px' }} /> */}
                    <div style={{ display: 'flex', alignItems: 'center' }}>
                        <img src={portolaAppIcon} id="banner-logo" style={{ height: '34px' }} />
                        <img
                            src={dragonAppDarkerLogo}
                            id="banner-logo"
                            style={{ height: '34px' }}
                        />
                    </div>
                </Box>
            }
        </AppBar>
    );
}

export function CustomIcon() {
    return (
        <Box
            sx={{
                width: '1.5rem',
                height: '1.5rem',
                bgcolor: 'black',
                borderRadius: '999px',
                display: 'flex',
                justifyContent: 'center',
                alignItems: 'center',
                alignSelf: 'center',
                backgroundImage:
                    'linear-gradient(135deg, hsl(210, 98%, 60%) 0%, hsl(210, 100%, 35%) 100%)',
                color: 'hsla(210, 100%, 95%, 0.9)',
                border: '1px solid',
                borderColor: 'hsl(210, 100%, 55%)',
                boxShadow: 'inset 0 2px 5px rgba(255, 255, 255, 0.3)',
            }}
        >
            <DashboardRoundedIcon color="inherit" sx={{fontSize: '1rem'}}/>
        </Box>
    );
}

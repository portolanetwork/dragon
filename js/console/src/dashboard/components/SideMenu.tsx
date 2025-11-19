import * as React from 'react';
import {styled} from '@mui/material/styles';
import Avatar from '@mui/material/Avatar';
import MuiDrawer, {drawerClasses} from '@mui/material/Drawer';
import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import MenuContent from './MenuContent';
import OptionsMenu from './OptionsMenu';
import {useAuth0} from '@auth0/auth0-react';
import {MenuContentProps} from "./types/MenuContentProps";


const drawerWidth = 240;

const Drawer = styled(MuiDrawer)({
    width: drawerWidth,
    flexShrink: 0,
    boxSizing: 'border-box',
    mt: 10,
    [`& .${drawerClasses.paper}`]: {
        width: drawerWidth,
        boxSizing: 'border-box',
    },
});


export default function SideMenu({
                                     mainListItems,
                                     middleListItems,
                                     secondaryListItems
                                 }: MenuContentProps) {
    const { user, isLoading } = useAuth0();

    if (isLoading) {
        return <div>Loading...</div>;
    }

    let photoURL = user?.picture ?? undefined;
    let displayName = user?.name ?? undefined;
    let email = user?.email;

    return (
        <Box
            sx={{
                width: 240,
                flexShrink: 0,
                boxSizing: 'border-box',
                height: '94vh',
                backgroundColor: 'background.paper',
                display: 'flex',
                flexDirection: 'column',
                borderRight: '1px solid',
                borderColor: 'divider',

                [`& .${drawerClasses.paper}`]: {
                    backgroundColor: 'background.paper',
                },
            }}
        >
            <Box
                sx={{
                    display: 'flex',
                    mt: 'calc(var(--template-frame-height, 0px) + 4px)',
                    p: 1.5,
                }}
            >
                <Box sx={{p: 1}}/>
            </Box>
            { /* <Divider /> */}
            <Box
                sx={{
                    overflow: 'auto',
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                }}
            >
                <MenuContent mainListItems={mainListItems} middleListItems={middleListItems} secondaryListItems={secondaryListItems}/>
            </Box>
            <Stack
                direction="row"
                sx={{
                    p: 2,
                    gap: 1,
                    alignItems: 'center',
                    borderTop: '1px solid',
                    borderColor: 'divider',
                }}
            >
                <Avatar
                    sizes="small"
                    alt={displayName}
                    src={photoURL}
                    sx={{width: 36, height: 36}}
                />
                <Box sx={{mr: 'auto'}}>
                    <Typography variant="body2" sx={{fontWeight: 500, lineHeight: '16px'}}>
                        {displayName}
                    </Typography>
                    <Typography variant="caption" sx={{color: 'text.secondary'}}>
                        {email}
                    </Typography>
                </Box>
                <OptionsMenu/>
            </Stack>
        </Box>
    );
}

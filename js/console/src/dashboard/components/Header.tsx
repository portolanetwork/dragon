import * as React from 'react';
import Stack from '@mui/material/Stack';
import NotificationsRoundedIcon from '@mui/icons-material/NotificationsRounded';
import CustomDatePicker from './CustomDatePicker';
import NavbarBreadcrumbs from './NavbarBreadcrumbs';
import MenuButton from './MenuButton';
//import Button from '@mui/material/Button';
import ColorModeIconDropdown from '../../shared-theme/ColorModeIconDropdown';

import Search from './Search';
import {HelpOutlineRounded, HelpRounded} from "@mui/icons-material";
import {Box} from "@mui/system";

import {Button} from '@mui/material';
import AppleIcon from "@mui/icons-material/Apple";

interface HeaderProps {
    selectedMenuItem: string[];
    onDrawerToggle: () => void;
}

const Header: React.FC<HeaderProps> = ({selectedMenuItem, onDrawerToggle}) => {

    const handleDownloadClick = async () => {
        let appCastUrl = `https://portolanetwork.github.io/portola-appcast/appcast.xml`;

        const currentDomain = window.location.hostname;
        console.log("Current Domain:", currentDomain);

        if (currentDomain === "portola.app") {
            appCastUrl = `https://portolanetwork.github.io/portola-appcast/appcast.xml`;
        } else if (currentDomain === "staging-app.portolanetwork.com" || currentDomain === "localhost") {
            appCastUrl = `https://portolanetwork.github.io/portola-staging-appcast/appcast.xml`;
        }

        console.log("AppCast URL:", appCastUrl);

        try {
            const response = await fetch(appCastUrl);
            if (!response.ok) throw new Error("Failed to fetch appcast.xml");

            const xmlText = await response.text();
            const parser = new DOMParser();
            const xmlDoc = parser.parseFromString(xmlText, "application/xml");

            const enclosure = xmlDoc.querySelector("enclosure");
            if (enclosure) {
                const fileUrl = enclosure.getAttribute("url");
                if (fileUrl) {
                    window.location.href = fileUrl; // Trigger the download
                    console.log("Download triggered for:", fileUrl);
                }
            }
        } catch (error) {
            console.error("Error fetching or parsing appcast.xml:", error);
        }
    };

    return (
        <Stack
            direction="row"
            sx={{
                display: {xs: 'none', md: 'flex'},
                width: '100%',
                alignItems: {xs: 'flex-start', md: 'center'},
                justifyContent: 'space-between',
                maxWidth: {sm: '100%', md: '1700px'},
                pt: 2,
            }}
            spacing={2}
        >
            <NavbarBreadcrumbs selectedMenuItem={selectedMenuItem}/>
            <Stack direction="row">
                { /* We will add the Search component here
        <Search />
        <CustomDatePicker />
        <MenuButton showBadge aria-label="Open notifications">
          <NotificationsRoundedIcon />
        </MenuButton>
        <ColorModeIconDropdown />
                  */
                    <>
                    <Button
                        variant="outlined"
                        color="primary"
                        onClick={handleDownloadClick}
                        sx={{margin: '10px'}}
                        startIcon={<AppleIcon/>}
                    >
                        Download for macOS
                    </Button>


                        <MenuButton aria-label="Open notifications" onClick={onDrawerToggle}>
                            <HelpRounded/>
                        </MenuButton>
                    </>

                }

            </Stack>
        </Stack>
    );
}

export default Header;

import * as React from 'react';
import type {} from '@mui/x-date-pickers/themeAugmentation';
import type {} from '@mui/x-charts/themeAugmentation';
import type {} from '@mui/x-data-grid/themeAugmentation';
import type {} from '@mui/x-tree-view/themeAugmentation';
import {useState} from 'react';
import { alpha } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import AppNavbar from './components/AppNavbar';
import Header from './components/Header';
import MainGrid from './components/MainGrid';
import SideMenu from './components/SideMenu';
import AppTheme from '../shared-theme/AppTheme';
import EndpointGrid from './components/EndpointGrid';
import AllTunnelsGrid from './components/AllTunnelsGrid';
import Settings from './components/Settings';
import Drawer from '@mui/material/Drawer';
import Typography from '@mui/material/Typography';
import Toolbar from '@mui/material/Toolbar';
import HelpContent from './components/HelpContent';
import FileFinder from './components/FileFinder';
import ChatWindow from './components/ChatWindow';
import NewChatWindow from './components/NewChatWindow';
import { Chat } from './components/types/Chat';
import { MenuItem } from './components/types/MenuContentProps';

import HomeRoundedIcon from '@mui/icons-material/HomeRounded';
import AnalyticsRoundedIcon from '@mui/icons-material/AnalyticsRounded';
import PeopleRoundedIcon from '@mui/icons-material/PeopleRounded';
import SettingsRoundedIcon from '@mui/icons-material/SettingsRounded';
import SignpostIcon from '@mui/icons-material/Signpost';
import ChatIcon from '@mui/icons-material/Chat';
import VpnKeyIcon from '@mui/icons-material/VpnKey';
import StorageIcon from '@mui/icons-material/Storage';



import {
  chartsCustomizations,
  //dataGridCustomizations,
  //datePickersCustomizations,
  treeViewCustomizations,
} from './theme/customizations';
import AllExportsGrid from "./components/AllExportsGrid";
import AuthClientGrid from "./components/AuthClientGrid";
import McpServersGrid from "./components/McpServersGrid";
import SpyderProxy from "../spyder_proxy/SpyderProxy";
import {useEffect} from "react";
import {useAuthState} from "react-firebase-hooks/auth";


const xThemeComponents = {
  ...chartsCustomizations,
  //...dataGridCustomizations,
  //...datePickersCustomizations,
  ...treeViewCustomizations,
};

import { auth } from "../firebase";
import {faker} from "@faker-js/faker";

const drawerWidth = 400;


export default function Dashboard(props: { disableCustomTheme?: boolean }) {
    const [selectedMenuItem, setSelectedMenuItem] = React.useState<string[]>(['New Chat']);
    //const [open, setOpen] = React.useState(false);
    const [drawerOpen, setDrawerOpen] = React.useState(false);
    const [chatList, setChatList] = useState<Chat[]>([]);
    const [user] = useAuthState(auth);
    //const [firstMessages, setFirstMessages] = useState<Map<string, string>>(new Map());
    const [firstMessage, setFirstMessage] = useState<string | null>(null);
    const [firstMessageChatId, setFirstMessageChatId] = useState<string | null>(null);

    const [mainMenuProps, setMainMenuProps] = useState<MenuItem[]>([]);
    const [middleMenuProps, setMiddleMenuProps] = useState<MenuItem[]>([]);
    const [secondaryMenuProps, setSecondaryMenuProps] = useState<MenuItem[]>([]);

    /*
    const toggleDrawer = (newOpen: boolean) => () => {
        setOpen(newOpen);
    };
     */

    if (!user) {
        throw new Error("User is not authenticated");
    }

    useEffect(() => {
        setMainMenuProps(mainListItems(chatList, handleMenuItemClick));
        setMiddleMenuProps(middleListItems(chatList, handleMenuItemClick));
        setSecondaryMenuProps(secondaryListItems(handleMenuItemClick));
    }, [selectedMenuItem, chatList]);


    useEffect(() => {
        const fetchAllChat = async () => {
            console.log("Fetching all chats for user:", user.uid);
            await getAllChat();
        };

        fetchAllChat();
    }, [user]);

    const handleDrawerToggle = () => {
        setDrawerOpen(!drawerOpen);
    };

    const handleMenuItemClick = (menuItem: string[]) => {
        console.log(`Menu item clicked: ${menuItem}`);
        setSelectedMenuItem(menuItem);
    };

    const mainListItems = (
        chatList: Chat[] = [],
        onMenuItemClick: (menuItem: string[]) => void
    ): MenuItem[] => {
        //console.log("-------------chatList: ", chatList);
        //console.log("-------------selectedMenuItem: ", selectedMenuItem);

        return [
            //{ text: 'Endpoints', isSelected:selectedMenuItem.at(0) == 'Endpoints', icon: <HomeRoundedIcon />, onClick: () => onMenuItemClick(['Endpoints']) },
            //{ text: 'Tunnels', isSelected:selectedMenuItem.at(0) == 'Tunnels', icon: <AnalyticsRoundedIcon/>, onClick: () => onMenuItemClick(['Tunnels']) },
            //{ text: 'Exports', isSelected:selectedMenuItem.at(0) == 'Exports' , icon: <PeopleRoundedIcon />, onClick: () => onMenuItemClick(['Exports']) },
            //{ text: 'File Manager', isSelected:selectedMenuItem.at(0) == 'File Manager', icon: <SignpostIcon />, onClick: () => onMenuItemClick(['File Manager']) },

            { text: 'MCP Servers', isSelected:selectedMenuItem.at(0) == 'MCP Servers', icon: <StorageIcon />, onClick: () => onMenuItemClick(['MCP Servers']) },
            { text: 'New Chat', isSelected:selectedMenuItem.at(0) == 'New Chat' ,icon: <ChatIcon />, onClick: () => onMenuItemClick(['New Chat']) },
            /*
            { text: 'Chats', isDisabled: true, isSelected:false, icon: <SignpostIcon />, onClick: () => onMenuItemClick(['Chat']),
                children: chatList.map((chat: Chat) => ({
                    text: chat.title || 'Unnamed Chat',
                    //isSelected: selectedMenuItem.at(0) === 'Chat' && selectedMenuItem.at(1) === `${chat.title}::${chat.id}`,
                    //onClick: () => onMenuItemClick(['Chat', `${chat.title}::${chat.id}`]),
                    isSelected: selectedMenuItem.at(0) === 'Chat' && selectedMenuItem.at(1) === `${chat.id}`,
                    onClick: () => onMenuItemClick(['Chat', `${chat.id}`]),
                    onDelete: () => handleDeleteChat(chat.id), // <-- add this
                })),
            },
             */

        ]};

    const middleListItems = (chatList: Chat[] = [], onMenuItemClick: (menuItem: string[]) => void) => [
        { text: 'Chats', isDisabled: true, isSelected:false, icon: <SignpostIcon />, onClick: () => onMenuItemClick(['Chat']),
            children: chatList.map((chat: Chat) => ({
                text: chat.title || 'Unnamed Chat',
                //isSelected: selectedMenuItem.at(0) === 'Chat' && selectedMenuItem.at(1) === `${chat.title}::${chat.id}`,
                //onClick: () => onMenuItemClick(['Chat', `${chat.title}::${chat.id}`]),
                isSelected: selectedMenuItem.at(0) === 'Chat' && selectedMenuItem.at(1) === `${chat.id}`,
                onClick: () => onMenuItemClick(['Chat', `${chat.id}`]),
                onDelete: () => handleDeleteChat(chat.id), // <-- add this
            })),
        },
    ];

    const secondaryListItems = (onMenuItemClick: (menuItem: string[]) => void) => [
        { text: 'Endpoints', isSelected:selectedMenuItem.at(0) == 'Endpoints', icon: <HomeRoundedIcon />, onClick: () => onMenuItemClick(['Endpoints']) },
        { text: 'Tunnels', isSelected:selectedMenuItem.at(0) == 'Tunnels', icon: <AnalyticsRoundedIcon/>, onClick: () => onMenuItemClick(['Tunnels']) },
        { text: 'File Manager', isSelected:selectedMenuItem.at(0) == 'File Manager', icon: <SignpostIcon />, onClick: () => onMenuItemClick(['File Manager']) },
        { text: 'Auth Clients', isSelected:selectedMenuItem.at(0) == 'Auth Clients', icon: <VpnKeyIcon />, onClick: () => onMenuItemClick(['Auth Clients']) },
        { text: 'Settings', isSelected:selectedMenuItem.at(0) == 'Settings', icon: <SettingsRoundedIcon />, onClick: () => onMenuItemClick(['Settings']) },
    ];

    const handleDeleteChat = async (chatId: string) => {
        console.log("Delete chat with ID:", chatId);

        if (chatId === selectedMenuItem.at(1)) {
            setSelectedMenuItem(['New Chat']); // or any other default menu item
        }

        await SpyderProxy.getInstance().deleteChat(user, chatId).then(async () => {
            console.log("Chat deleted successfully");
            await getAllChat(); // Refresh chat list
        }).catch((error) => {
            console.error("Error deleting chat:", error);
        });
    };

    const getAllChat = async () => {
        await SpyderProxy.getInstance().getAllChat(user).then((chats) => {
            console.log("Fetched chats:", chats);
            const sortedChats = chats.sort(
                (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
            );

            setChatList(sortedChats);
        }).catch((error) => {
            console.error("Error fetching chats:", error);
        });
    };

    const createChat = async (title: string) => {
        console.log("Creating chat with title:", title);
        return await SpyderProxy.getInstance().createChat(user, title).then(async (chat) => {
            console.log("Chat created:", chat);
            await getAllChat(); // Refresh chat list
            return chat; // <-- Ensure this is returned
        }).catch((error) => {
            console.error("Error creating chat:", error);
            throw error;
        });
    };

    const handleCreateChat = async (firstMessage: string) => {
        try {
            const chat = await createChat("New Chat");
            setFirstMessage(firstMessage);
            setFirstMessageChatId(chat.id);
            setSelectedMenuItem(['Chat', `${chat.id}`]);
        } catch (error) {
            console.error("Error creating chat from NewChatWindow:", error);
        }
    };



    return (
    <AppTheme {...props} themeComponents={xThemeComponents}>
        {  // Some padding behind the app bar
            <Box sx={{ height: '6vh'}}></Box>
        }
      <CssBaseline enableColorScheme />
      <Box sx={{ display: 'flex', mt: 0 }}>
        <SideMenu
            mainListItems={mainMenuProps}
            middleListItems={middleMenuProps}
            secondaryListItems={secondaryMenuProps}
        />

        <AppNavbar />

          {/* Main content */}
        <Box
          component="main"
          sx={(theme) => ({
            flexGrow: 1,
            backgroundColor: theme.vars
              ? `rgba(${theme.vars.palette.background.defaultChannel} / 1)`
              : alpha(theme.palette.background.default, 1),
            overflow: 'auto',
              marginRight: drawerOpen ? 0: `${-drawerWidth}px` ,
              transition: theme.transitions.create('margin', {
                  easing: theme.transitions.easing.sharp,
                  duration: theme.transitions.duration.leavingScreen,
              }),
          })}
        >
            <Box sx={{ height: '94vh', overflow: 'auto' }}>
                <Stack
                    spacing={2}
                    sx={{
                        alignItems: 'center',
                        mx: 3, // Right margin
                        //pb: 5,
                        mt: { xs: 8, md: 0 },
                    }}
                >
                    <Header selectedMenuItem={selectedMenuItem} onDrawerToggle={handleDrawerToggle} />
                    {selectedMenuItem.at(0) === 'Endpoints' && <EndpointGrid />}
                    {selectedMenuItem.at(0) === 'Tunnels' && <AllTunnelsGrid />}
                    {selectedMenuItem.at(0) === 'Exports' && <AllExportsGrid />}
                    {selectedMenuItem.at(0) === 'File Manager' && <FileFinder />}
                    {selectedMenuItem.at(0) === 'MCP Servers' && <McpServersGrid />}
                    {selectedMenuItem.at(0) === 'New Chat' && <NewChatWindow onCreateChat={handleCreateChat} />}
                    {selectedMenuItem.at(0) === 'Chat' && <ChatWindow
                        chatId={selectedMenuItem.at(1) ?? ''}
                        reloadChatList={getAllChat}
                        firstMessage={selectedMenuItem.at(1) === firstMessageChatId ? firstMessage ?? undefined : undefined}
                        onFirstMessageSent={() => {
                            setFirstMessage(null);
                            setFirstMessageChatId(null);
                        }}/>}
                    {selectedMenuItem.at(0) === 'Auth Clients' && <AuthClientGrid />}
                    {selectedMenuItem.at(0) === 'Settings' && <Settings />}
                </Stack>
            </Box>
        </Box>

          <Drawer
              ////variant="permanent"
              variant="persistent"
              anchor="right"
              //open={drawerOpen}

              //variant="permanent"
              sx={{
                  width: drawerWidth,
                  flexShrink: 0,
                  mt: 1,
                  [`& .MuiDrawer-paper`]: { width: drawerWidth, boxSizing: 'border-box' },
              }}

              //open={isHelpDrawerOpen}
              //onClose={toggleHelpDrawer(false)}
              open={drawerOpen}
          >
              <Box sx={{ height: '10vh'}}></Box>

              { /* <Toolbar /> */ }

              <Box sx={{ overflow: 'auto', ml: 2, mr: 2 }}>
                  <HelpContent selectedMenuItem={selectedMenuItem} />
              </Box>
          </Drawer>

      </Box>

    </AppTheme>
  );
}

import React, {useState, useRef, useEffect} from 'react';
import {
    Box,
    TextField,
    Fab,
    CircularProgress,
} from '@mui/material';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import Typography from '@mui/material/Typography';
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import FolderIcon from '@mui/icons-material/Folder';
import DescriptionIcon from '@mui/icons-material/Description';
import SearchIcon from '@mui/icons-material/Search';
import MemoryIcon from '@mui/icons-material/Memory';

interface NewChatWindowProps {
    onCreateChat: (firstMessage: string) => void;
}

export default function NewChatWindow({onCreateChat}: NewChatWindowProps) {
    const [newMessage, setNewMessage] = useState<string>('');
    const [loading, setLoading] = useState<boolean>(false);
    const inputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        if (inputRef.current) {
            inputRef.current.focus();
        }
    }, []);

    const handleSendMessage = () => {
        if (newMessage.trim() && !loading) {
            setLoading(true);
            onCreateChat(newMessage.trim());
            setNewMessage('');
            setLoading(false);
        }
    };

    const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            handleSendMessage();
        }
    };

    return (
        <Box
            sx={{
                width: '100%',
                height: '83vh',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexDirection: 'column',

            }}
        >
            <Box sx={{
                maxWidth: '50%',
                width: '100%',
            }}>
                <Box sx={{
                    boxShadow: (theme) => theme.palette.mode === 'dark'
                        ? '0 4px 20px rgba(255, 255, 255, 0.1), 0 2px 8px rgba(255, 255, 255, 0.05)'
                        : 3,
                    minHeight: 72,
                    bgcolor: 'background.paper',
                    borderRadius: 1,
                    border: (theme) => theme.palette.mode === 'dark'
                        ? '1px solid rgba(255, 255, 255, 0.12)'
                        : 'none',
                }}>
                    <Box sx={{p: 2, display: 'flex', gap: 1, alignItems: 'flex-end'}}>
                        <TextField
                            fullWidth
                            variant="standard"
                            placeholder="Start a new conversation..."
                            value={newMessage}
                            onChange={(e) => setNewMessage(e.target.value)}
                            onKeyDown={handleKeyDown}
                            multiline
                            minRows={1}
                            maxRows={6}
                            disabled={loading}
                            inputRef={inputRef}
                        />
                        <Fab
                            color="primary"
                            onClick={handleSendMessage}
                            disabled={loading || !newMessage.trim()}
                            sx={{
                                minWidth: 0,
                                width: 40,
                                height: 40,
                                boxShadow: 0,
                                borderRadius: '50%',
                                opacity: !newMessage.trim() ? 0.5 : 1,
                                transition: 'opacity 0.2s',
                            }}
                        >
                            {loading ? (
                                <CircularProgress size={20} color="inherit" />
                            ) : (
                                newMessage.trim() && <ArrowUpwardIcon />
                            )}
                        </Fab>
                    </Box>
                </Box>
            </Box>
            <Box sx={{ maxWidth: '50%', width: '100%', mt: 3, p: 2 }}>
                <Typography variant="h6" color="primary.dark" gutterBottom>
                    Here is what you can do
                </Typography>
                <List sx={{ width: '100%' }}>
                    {/* Search */}
                    <ListItem sx={{ flexDirection: 'column', alignItems: 'flex-start', pb: 0 }} disableGutters>
                        <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5 }}>
                            <SearchIcon color="primary" sx={{ mr: 1 }} />
                            <Typography variant="body2" sx={{ fontWeight: 'bold' }}>
                                Search
                            </Typography>
                        </Box>
                        <Box component="ul" sx={{ pl: 5, m: 0, pb: 1 }}>
                            <li style={{ margin: 0, padding: 0 }}>
                                <Typography variant="body2" component="span">
                                    Search your files and documents. Example: "Search for tax docs on my-device"
                                </Typography>
                            </li>
                        </Box>
                    </ListItem>
                    {/* File system */}
                    <ListItem sx={{ flexDirection: 'column', alignItems: 'flex-start', pb: 0 }} disableGutters>
                        <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5 }}>
                            <FolderIcon color="primary" sx={{ mr: 1 }} />
                            <Typography variant="body2" sx={{ fontWeight: 'bold' }}>
                                File system
                            </Typography>
                        </Box>
                        <Box component="ul" sx={{ pl: 5, m: 0, pb: 1 }}>
                            <li style={{ margin: 0, padding: 0 }}>
                                <Typography variant="body2" component="span">
                                    Read file systems dir listings. Example: "Read files in /home/user/docs on my-device"
                                </Typography>
                            </li>
                        </Box>
                    </ListItem>
                    {/* Document RAG */}
                    <ListItem sx={{ flexDirection: 'column', alignItems: 'flex-start', pb: 0 }} disableGutters>
                        <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5 }}>
                            <DescriptionIcon color="primary" sx={{ mr: 1 }} />
                            <Typography variant="body2" sx={{ fontWeight: 'bold' }}>
                                Document RAG
                            </Typography>
                        </Box>
                        <Box component="ul" sx={{ pl: 5, m: 0, pb: 1 }}>
                            <li style={{ margin: 0, padding: 0 }}>
                                <Typography variant="body2" component="span">
                                    Read documents and query their content (PDF, Word, Excel, Text, CSV, etc.). Example: "Read the document /home/user/docs/report.pdf on my-device"
                                </Typography>
                            </li>
                        </Box>
                    </ListItem>
                    {/* Remote Access */}
                    <ListItem sx={{ flexDirection: 'column', alignItems: 'flex-start', pb: 0 }} disableGutters>
                        <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5 }}>
                            <MemoryIcon color="primary" sx={{ mr: 1 }} />
                            <Typography variant="body2" sx={{ fontWeight: 'bold' }}>
                                Remote Access
                            </Typography>
                        </Box>
                        <Box component="ul" sx={{ pl: 5, m: 0, pb: 1 }}>
                            <li style={{ margin: 0, padding: 0 }}>
                                <Typography variant="body2" component="span">
                                    Query system activity. Example: "What processes are using the most memory on my-device?"
                                </Typography>
                            </li>
                            <li style={{ margin: 0, padding: 0 }}>
                                <Typography variant="body2" component="span">
                                    Create SSH service and forward it to another device. Example: "Create SSH service on vega and export it to phoenix port 47000 with public key auth"
                                </Typography>
                            </li>
                            <li style={{ margin: 0, padding: 0 }}>
                                <Typography variant="body2" component="span">
                                    Port Forward a TCP service from one device to another. Example: "Create a Port Forward from server on vega port 80 to phoenix port 48000"
                                </Typography>
                            </li>
                        </Box>
                    </ListItem>
                </List>

            </Box>
        </Box>
    );
}
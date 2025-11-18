import React, {useState, useRef, useEffect, useCallback} from 'react';
import {
    Box,
    TextField,
    Stack,
    ListItem,
    ListItemText,
    Paper,
    Typography
} from '@mui/material';
import {faker} from '@faker-js/faker';
import {SpyderWssClient} from '../../spyder_wss/SpyderWssClient';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter';
import './chat-markdown.css';
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import {Fab} from '@mui/material';
import {useTheme} from '@mui/material/styles';
import {materialDark} from 'react-syntax-highlighter/dist/esm/styles/prism';
import {materialLight} from 'react-syntax-highlighter/dist/esm/styles/prism';
import {CircularProgress} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import MermaidComponent from './MermaidComponent';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import axios from 'axios';
import SpyderImage from './SpyderImage';
import SpyderLink from './SpyderLink';


type CodeProps = {
    node?: unknown;
    inline?: boolean;
    className?: string;
    children?: React.ReactNode;
    [key: string]: any;
};


// In your ChatWindow.tsx
const code = ({node, inline, className, children, ...props}: CodeProps) => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const style = isDark ? materialDark : materialLight;
    const match = /language-(\w+)/.exec(className || '');
    const background = theme.palette.background.paper;
    const content = String(children).replace(/\n$/, '');

    // Check if this is a Mermaid diagram
    if (!inline && match && match[1] === 'mermaid') {
        return <MermaidComponent chart={content}/>;
    }

    return !inline && match ? (
        <SyntaxHighlighter
            style={{...style, 'hljs': {...style.hljs, background}}}
            language={match[1]}
            PreTag="div"
            {...props}
        >
            {content}
        </SyntaxHighlighter>
    ) : (
        <code className={className} style={{background}} {...props}>
            {children}
        </code>
    );
};

interface IncomingChatMessage {
    messageType?: 'CHAT' | 'EVENT' | 'TOOL' | 'ERROR';
    messageId?: string;
    content: string;
    from?: 'USER' | 'ASSISTANT';
    event?: string;
    error?: string;
    status?: 'BEGIN' | 'END';
}

interface Message {
    id: string;
    content: string;
    timestamp: Date;
    isUser: boolean;
}

interface ChatWindowProps {
    chatId: string;
    reloadChatList: () => void;
    firstMessage?: string;
    onFirstMessageSent?: () => void;
}

// Module-level cache for SpyderImage elements by src
//const spyderImageElementCache = new Map<string, JSX.Element>();

function SpyderImageWrapper({src, alt}: { src?: string, alt?: string }) {
    if (!src) return null;
    console.log('++++++++++++++++Rendering image with src:', src);
    // Use a spinner as placeholder while loading
    const placeholder = <CircularProgress size={32} />;
    return <SpyderImage src={src} alt={alt} loadingComponent={placeholder} />;
}

export default function ChatWindow({chatId, reloadChatList, firstMessage, onFirstMessageSent}: ChatWindowProps) {
    const [messages, setMessages] = useState<Message[]>([]);
    const inputRef = useRef<HTMLInputElement>(null);
    const [inputHasValue, setInputHasValue] = useState(false);
    const messagesEndRef = useRef<HTMLDivElement>(null);
    const [wss, setWss] = useState<SpyderWssClient | null>(null);
    const [loading, setLoading] = useState<boolean>(false);
    const [eventMessages, setEventMessages] = useState<
        Map<string, { icon: React.ReactNode; message: string; timestamp: number }>
    >(new Map());

    const loadedMessageIds = useRef<Set<string>>(new Set());

    const messageRefs = useRef<{ [key: string]: HTMLLIElement | null }>({});

    const [scrollToMessageId, setScrollToMessageId] = useState<string | null>(null);
    const [showScrollToBottom, setShowScrollToBottom] = useState<boolean>(false);
    const scrollContainerRef = useRef<HTMLDivElement>(null);

    //const chatId = chatId//chatTitleAndId?.split('::')[1] ?? '';
    const assistantMessageCountRef = useRef(0);

    const [user] = useAuthState(auth);

    if (!user) {
        throw new Error("User is not authenticated");
    } else {
        //console.log('ChatWindow user:', user);
    }

    useEffect(() => {
        // Reset state and reconnect WebSocket when reloadFlag changes
        setMessages([]);
        setEventMessages(new Map());
        setWss(null);
        setLoading(false);
        loadedMessageIds.current.clear();
        // Optionally reset other state as needed
    }, [chatId]);

    //console.log('ChatWindow chatId:', chatId);

    //console.log('ChatWindow user:', user);

    const connectingRef = useRef(false);

    useEffect(() => {
        if (!user || wss || connectingRef.current) return;

        connectingRef.current = true;

        console.log('Connecting to WebSocket for chatId:', chatId);

        const connectWss = async () => {
            try {
                const token = await user.getIdToken();
                //const wsUrl = `wss://spyderweb.staging-app.portolanetwork.com/wss/dragon/chat?auth=${encodeURIComponent(token)}&chatId=${encodeURIComponent(chatId)}`;
                const wssClient = new SpyderWssClient(
                    //wsUrl,
                    //handleMessage,
                    token,
                    chatId,
                    handleMessage,
                    () => {
                        console.log('--- WebSocket connection open');
                        setWss(wssClient);
                        connectingRef.current = false;
                    },
                    (error) => {
                        console.error('--- WebSocket error:', error);
                        setWss(null);
                        connectingRef.current = false;
                    },
                    () => {
                        console.log('--- WebSocket closed');
                        setWss(null);
                        connectingRef.current = false;
                    }
                );
                wssClient.connect();
            } catch {
                connectingRef.current = false;
            }
        };

        connectWss();
    }, [user, wss, chatId]);


    useEffect(() => {
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({behavior: 'smooth'});
        }
    }, [messages, eventMessages]);

    const handleScroll = () => {
        if (scrollContainerRef.current) {
            const {scrollTop, scrollHeight, clientHeight} = scrollContainerRef.current;
            const isAtBottom = scrollHeight - scrollTop - clientHeight < 50; // 50px threshold
            setShowScrollToBottom(!isAtBottom);
        }
    };

    const scrollToBottom = () => {
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({behavior: 'smooth'});
        }
    };

    useEffect(() => {
        if (scrollToMessageId && messageRefs.current[scrollToMessageId]) {
            messageRefs.current[scrollToMessageId]?.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
    }, [scrollToMessageId, messages]);

    const handleMessage = (data: string) => {
        try {
            const chatMessage: IncomingChatMessage = JSON.parse(data);

            console.log('Received message:', chatMessage);

            //reloadChatList();

            if (chatMessage.messageType === "CHAT") {
                const id = chatMessage.messageId ?? faker.string.uuid();
                if (loadedMessageIds.current.has(id)) {
                    console.log('Ignoring duplicate message with ID:', id);
                    return; // Ignore duplicate message
                } else {
                    console.log('Processing new message with ID:', id);
                    loadedMessageIds.current.add(id);
                }

                setMessages(prev => [
                    ...prev,
                    {
                        id: chatMessage.messageId ?? faker.string.uuid(),
                        //sender: 'Dragon',
                        content: chatMessage.content ?? '',
                        timestamp: new Date(),
                        isUser: chatMessage.from === 'USER',
                        //messageType: 'CHAT',
                    },
                ]);

                if (chatMessage.from === 'ASSISTANT') {
                    if (assistantMessageCountRef.current % 2 === 1) {
                        console.log('Reloading chat list after assistant message count:', assistantMessageCountRef.current);
                        reloadChatList();
                    }
                    assistantMessageCountRef.current += 1;

                    setLoading(false);
                } else if (chatMessage.from === 'USER') {
                    setScrollToMessageId(chatMessage.messageId ?? null);
                }
                setEventMessages(new Map());
            } else if (chatMessage.messageType === "EVENT" && chatMessage.event) {
                setEventMessages(prev => {
                    const newMap = new Map(prev);
                    const now = Date.now();
                    if (chatMessage.status === 'BEGIN') {
                        newMap.set(chatMessage.event!, {
                            icon: <CircularProgress color="info" size={12}/>,
                            message: chatMessage.content ?? chatMessage.event!,
                            timestamp: now,
                        });
                    } else if (chatMessage.status === 'END') {
                        let beginTimestamp = newMap.get(chatMessage.event!)?.timestamp || now;

                        newMap.set(chatMessage.event!, {
                            icon: <CheckCircleIcon color="success" sx={{fontSize: 14}}/>,
                            message: chatMessage.content ?? chatMessage.event!,
                            timestamp: beginTimestamp,
                        });
                    }
                    return newMap;
                });
            } else if (chatMessage.messageType === "TOOL") {
                // Handle TOOL messages if needed
            } else if (chatMessage.messageType === "ERROR" || chatMessage.error) {
                console.error('Received ERROR message:', chatMessage);
                setMessages(prev => [
                    ...prev,
                    {
                        id: faker.string.uuid(),
                        sender: 'System',
                        content: `Error: ${chatMessage.error ?? chatMessage.content ?? 'Unknown error'}`,
                        timestamp: new Date(),
                        isUser: false,
                        messageType: 'CHAT',
                    },
                    //lastOutgoingMessageRef.current!,
                ]);
                setLoading(false);
                setEventMessages(new Map());
            }

            //setSplitView(true);
        } catch (e) {
            console.error('Failed to parse message:', data, e);
        }

    };

    const handleInput = () => {
        setInputHasValue(!!inputRef.current?.value.trim());
    };

    const handleSendMessage = () => {
        if (loading) return; // Prevent send if loading
        const value = inputRef.current?.value.trim();
        if (value && wss) {
            setLoading(true);
            wss.send(JSON.stringify({content: value, messageType: 'CHAT'}));
            setEventMessages(prev => {
                const newMap = new Map(prev);
                const now = Date.now();
                newMap.set("Processing: " + value, {
                    icon: <CircularProgress color="info" size={16}/>,
                    message: "Processing: " + value,
                    timestamp: now,
                });
                return newMap;
            });
            if (inputRef.current) inputRef.current.value = '';
            setInputHasValue(false);
        }
    };

    useEffect(() => {
        if (firstMessage && wss && !loading && messages.length === 0) {
            //setNewMessage({ content: firstMessage, messageType: 'CHAT' });
            setLoading(true);
            wss.send(JSON.stringify({content: firstMessage, messageType: 'CHAT'}));

            onFirstMessageSent?.();

            console.log('Sent initial message:', firstMessage);
            //console.log('initMessage set to null', initMessage);
        }
    }, [firstMessage, wss, loading, messages.length]);

    const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
        if (loading) return; // Prevent submit and Enter key from responding while loading
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            handleSendMessage();
        }
    };

    useEffect(() => {
        handleScroll();
    }, [messages, eventMessages]);

    useEffect(() => {
        if (inputRef.current) {
            inputRef.current.focus();
        }
    }, [messages]);

    // Construct SPYDER_STORAGE_PREFIX based on hostname logic
    let SPYDER_STORAGE_PREFIX: string;
    if (window.location.hostname === 'localhost') {
        SPYDER_STORAGE_PREFIX = 'https://spyderweb.staging-app.portolanetwork.com/storage';
    } else {
        SPYDER_STORAGE_PREFIX = `https://${window.location.hostname.replace('console', 'spyderweb')}/storage`;
    }

    // Memoize isSpyderStorageUrl so its reference is stable
    const isSpyderStorageUrl = useCallback((url: string) => {
        return url.startsWith(SPYDER_STORAGE_PREFIX);
    }, []);

    function getFileNameFromUrl(url: string) {
        try {
            return decodeURIComponent(url.split('/').pop() || 'download');
        } catch {
            return 'download';
        }
    }



    // In-memory cache for image object URLs
    const imageCache = new Map<string, string>();

    return (
        <Box
            sx={{
                width: '100%',
                maxWidth: {sm: '100%', md: '1700px'},
                margin: '0 auto',
                display: 'flex',
                height: '83vh',
            }}
        >

            <Box sx={{flex: 1, display: 'flex', flexDirection: 'column', position: 'relative'}}>
                <Box
                    ref={scrollContainerRef}
                    onScroll={handleScroll}
                    sx={{
                        flexGrow: 1,
                        overflowY: 'auto',
                        pb: 10,
                    }}
                >
                    {[...messages].map((message) => (
                        <React.Fragment key={message.id}>
                            <ListItem
                                key={message.id}
                                ref={el => {
                                    messageRefs.current[message.id] = el;
                                }}
                                sx={{
                                    //justifyContent: message.isUser ? 'flex-end' : 'flex-start',
                                    p: 0,
                                    alignItems: 'flex-start',
                                }}
                            >
                                <Stack
                                    direction="column"
                                    spacing={0.5}
                                    //alignItems={message.isUser ? 'flex-end' : 'flex-start'}
                                    sx={{
                                        //maxWidth: message.isUser ? '80%' : '100%',
                                        //width: message.isUser ? 'auto' : '100%'
                                        maxWidth: '100%',
                                        width: '100%',
                                    }}
                                >
                                    <Paper
                                        variant="highlighted"
                                        sx={{
                                            p: 1.5,
                                            borderRadius: '4px',
                                            borderTopLeftRadius: message.isUser ? '4px' : '4px',
                                            borderTopRightRadius: message.isUser ? '4px' : '4px',
                                            bgcolor: message.isUser
                                                ? 'primary.main'
                                                : 'transparent',
                                            color: message.isUser
                                                ? 'white'
                                                : 'text.primary',
                                            border: 'none',
                                            borderColor: 'transparent',
                                            wordBreak: 'break-word',
                                            width: message.isUser ? 'auto' : '100%',
                                            display: 'flex',
                                            alignItems: 'center',
                                            gap: 1,
                                        }}
                                    >
                                        <ListItemText
                                            primary={
                                                <div className="chat-markdown">
                                                    <ReactMarkdown
                                                        remarkPlugins={[remarkGfm]}
                                                        components={{
                                                            code,
                                                            img: SpyderImageWrapper,
                                                            a: (props) => <SpyderLink {...props} user={user} isSpyderStorageUrl={isSpyderStorageUrl} getFileNameFromUrl={getFileNameFromUrl} />,
                                                        }}
                                                    >
                                                        {message.content}
                                                    </ReactMarkdown>
                                                </div>
                                            }
                                            /*
                                            secondary={
                                                <Typography
                                                    variant="body2"
                                                    sx={{
                                                        mt: 0.5,
                                                        color: message.isUser
                                                            ? 'rgba(255,255,255,0.7)'
                                                            : '#00bcd4',
                                                        fontWeight: message.isUser ? 'normal' : 'bold',
                                                    }}
                                                >
                                                    {message.timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                                                </Typography>
                                            }
                                             */
                                            sx={{m: 0, flex: 1}}
                                        />
                                    </Paper>

                                </Stack>
                            </ListItem>
                            { /*<Box sx={{height: 16}}/> */}

                            {/*index % 2 === 1 && <Divider sx={{ my: 4, borderBottomWidth: 8 }} />*/}

                        </React.Fragment>


                    ))}


                    {Array.from(eventMessages.entries())
                        .sort(([, a], [, b]) => a.timestamp - b.timestamp)
                        .map(([eventKey, {icon, message}]) => (
                            <Paper
                                key={eventKey}
                                sx={{
                                    mt: 1,
                                    mb: 1,
                                    p: 0.5,
                                    //bgcolor: 'info.light',
                                    //color: 'info.contrastText',
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: 0.5,
                                    borderRadius: 2,
                                }}
                            >
                                {icon}
                                <Typography variant="caption">{message}</Typography>
                            </Paper>
                        ))}
                    <div ref={messagesEndRef}/>

                </Box>

                {showScrollToBottom && (
                    <Box sx={{
                        position: 'absolute',
                        bottom: 80,
                        //right: 16,
                        left: '50%',

                        zIndex: 1001,
                    }}>
                        <Fab
                            size="small"
                            //color="background"
                            onClick={scrollToBottom}
                            sx={{
                                width: 32,
                                height: 32,
                                bgcolor: 'background.paper',
                                color: 'text.primary',
                                boxShadow: 2,
                                '&:hover': {
                                    boxShadow: 4,
                                    bgcolor: 'background.paper',
                                }
                            }}
                        >
                            <ArrowDownwardIcon sx={{fontSize: 16}}/>
                        </Fab>
                    </Box>
                )}

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
                    zIndex: 1000,
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0
                }}>
                    <Box sx={{p: 2, display: 'flex', gap: 1}}>
                        <TextField
                            fullWidth
                            variant="standard"
                            placeholder="Type a message..."
                            inputRef={inputRef}
                            onKeyDown={handleKeyDown}
                            onInput={handleInput}
                            multiline
                            minRows={1}
                            maxRows={6}
                            sx={{'& .MuiOutlinedInput-root': {pr: 0.5}}}
                        />
                        <Box sx={{display: 'flex', alignItems: 'center', gap: 1}}>
                            <Fab
                                color="primary"
                                onClick={handleSendMessage}
                                disabled={loading || !inputHasValue}
                                sx={{
                                    minWidth: 0,
                                    width: 40,
                                    height: 40,
                                    boxShadow: 0,
                                    borderRadius: '50%',
                                    ml: 1,
                                    opacity: !inputHasValue ? 0.5 : 1,
                                    transition: 'opacity 0.2s',
                                }}
                            >

                                {loading ? (
                                    <CircularProgress size={20} color="inherit"/>
                                ) : (
                                    inputHasValue && <ArrowUpwardIcon/>
                                )}
                            </Fab>
                        </Box>
                    </Box>
                </Box>

            </Box>
        </Box>
    );
}

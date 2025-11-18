import * as React from 'react';
import Typography from '@mui/material/Typography';
import Breadcrumbs from '@mui/material/Breadcrumbs';
import Link from '@mui/material/Link';
import FolderIcon from '@mui/icons-material/Folder';
import FolderOpenIcon from '@mui/icons-material/FolderOpen';
import {EndpointFilepathRow} from './types/EndpointFilepathRow';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import RefreshIcon from '@mui/icons-material/Refresh';
import CreateDirDialog from './CreateDirDialog';
import CircleIcon from "@mui/icons-material/Circle";
import Tooltip from '@mui/material/Tooltip';


interface FsBreadcrumbsProps {
    homedir: string;
    endpointFilePath: EndpointFilepathRow;
    onDirectorySelect: (href: string) => void;
    onRefresh: () => void;
    onCreateDir: (dirName: string) => void;
}

import ChevronRightIcon from '@mui/icons-material/ChevronRight';

const FsBreadcrumbs: React.FC<FsBreadcrumbsProps> = ({homedir, endpointFilePath, onDirectorySelect, onRefresh, onCreateDir}) => {
    console.log('FsBreadcrumbs: endpointFilePath', endpointFilePath);

    const handleClick = (event: React.MouseEvent<HTMLAnchorElement, MouseEvent>, path: string) => {
        event.preventDefault();
        onDirectorySelect(path);
    };

    const items = endpointFilePath.path.split('/').filter(Boolean).map((label, index, arr) => {
        const href = '/' + arr.slice(0, index + 1).join('/');
        return {label, path: href};
    });

    return (
        <>
            <Breadcrumbs
                aria-label="breadcrumb"
                separator={<ChevronRightIcon fontSize="small"
                                             sx={{color: 'gray', mx: 0.1}}/>} // Adjust separator styles
                sx={{
                    '& .MuiBreadcrumbs-separator': {
                        marginLeft: '2px', // Adjust left margin
                        marginRight: '2px', // Adjust right margin
                    },
                }}
            >
                <Box sx={{display: 'flex', alignItems: 'center', gap: 1}}>
                    <Tooltip title={endpointFilePath.endpointIsOnline ? "Online" : "Offline"}>
                        <CircleIcon
                            sx={{
                                fontSize: 15,
                                color: endpointFilePath.endpointIsOnline ? '#00FF00' : '#808080',
                            }}
                        />
                    </Tooltip>
                    <Link
                        underline="hover"
                        sx={{fontSize: '0.875rem', color: '#00bcd4', textAlign: 'left', cursor: 'pointer'}}
                        onClick={(event) => {
                            event.preventDefault();
                            onDirectorySelect(homedir);
                        }}
                        href="/"
                    >
                        {endpointFilePath.hostname}
                    </Link>
                </Box>

                {items.map((item, index) => (
                    index < items.length - 1 ? (
                        <Link
                            key={item.path}
                            underline="hover"
                            sx={{display: 'flex', alignItems: 'center'}}
                            color="inherit"
                            href={item.path}
                            onClick={(event) => handleClick(event, item.path)}
                        >
                            <FolderIcon sx={{mr: 0.5}} fontSize="inherit"/>
                            {item.label}
                        </Link>
                    ) : (
                        <Typography
                            key={item.path}
                            sx={{color: 'text.primary', display: 'flex', alignItems: 'center'}}
                        >
                            <FolderOpenIcon sx={{mr: 0.5}} fontSize="inherit"/>
                            {item.label}
                        </Typography>
                    )
                ))}
            </Breadcrumbs>

            <Box sx={{display: 'flex', gap: 1, mt: 2}}>
                <Button
                    variant="contained"
                    color="primary"
                    size="small"
                    startIcon={<RefreshIcon/>}
                    onClick={onRefresh}
                    sx={{padding: '2px 8px', height: '12px', minHeight: '24px', fontSize: '0.75rem'}}
                >
                    Refresh
                </Button>

                {endpointFilePath.endpointIsOnline && <CreateDirDialog onCreate={onCreateDir}/>}
            </Box>
        </>
    );
};

export default FsBreadcrumbs;
import React, { useState } from 'react';
import axios from 'axios';
import { CircularProgress } from '@mui/material';

interface SpyderLinkProps {
    href?: string;
    children?: React.ReactNode;
    user: any;
    isSpyderStorageUrl: (url: string) => boolean;
    getFileNameFromUrl: (url: string) => string;
}

const SpyderLink: React.FC<SpyderLinkProps> = ({ href, children, user, isSpyderStorageUrl, getFileNameFromUrl }) => {
    const [downloading, setDownloading] = useState(false);

    const handleDownload = async () => {
        if (href && isSpyderStorageUrl(href) && user) {
            setDownloading(true);
            try {
                const token = await user.getIdToken();
                const response = await axios.get(href, {
                    headers: { Authorization: `Bearer ${token}` },
                    responseType: 'blob',
                });
                const blobUrl = URL.createObjectURL(response.data);
                const a = document.createElement('a');
                a.href = blobUrl;
                a.download = getFileNameFromUrl(href);
                document.body.appendChild(a);
                a.click();
                setTimeout(() => {
                    URL.revokeObjectURL(blobUrl);
                    document.body.removeChild(a);
                }, 1000);
            } finally {
                setDownloading(false);
            }
        }
    };

    if (href && isSpyderStorageUrl(href)) {
        return (
            <button onClick={handleDownload} disabled={downloading} style={{ color: '#1976d2', textDecoration: 'underline', background: 'none', border: 'none', cursor: 'pointer' }}>
                {href} {downloading && '[downloading...]'}
            </button>
        );
    }
    return <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>;
};

export default SpyderLink;

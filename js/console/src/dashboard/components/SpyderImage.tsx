import React, { useEffect, useState } from 'react';
import { useAuthState } from 'react-firebase-hooks/auth';
import { auth } from '../../firebase';

interface SpyderImageProps extends React.ImgHTMLAttributes<HTMLImageElement> {
  src: string; // remote image URL
  loadingComponent?: React.ReactNode; // optional loading indicator
  errorComponent?: React.ReactNode; // optional error indicator
}

const SpyderImage: React.FC<SpyderImageProps> = ({
  src,
  loadingComponent = <span>Loading image...</span>,
  errorComponent = <span>Failed to load image</span>,
  ...imgProps
}) => {
  const [user, loadingUser] = useAuthState(auth);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let isMounted = true;
    let url: string | null = null;
    setLoading(true);
    setError(false);
    setObjectUrl(null);

    const fetchImage = async () => {
      try {
        if (!user) {
          throw new Error('User is not authenticated');
        }
        const idToken = await user.getIdToken();
        const fetchHeaders: Record<string, string> = {
          'Authorization': `Bearer ${idToken}`
        };
        console.log('---------- Fetching image with headers:', fetchHeaders);

        const response = await fetch(src, { headers: fetchHeaders });

        console.log('---------- Fetch response:', response);

        if (!response.ok) throw new Error('Image fetch failed');
        const blob = await response.blob();
        url = URL.createObjectURL(blob);

        console.log('---------- Created object URL:', url);



        if (isMounted) {
          setObjectUrl(url);
          setLoading(false);
        }
      } catch (e) {
        if (isMounted) {
          setError(true);
          setLoading(false);
        }
      }
    };
    if (!loadingUser) {
        console.log('---------- User loaded, fetching image');

      fetchImage();
    }
    return () => {
        console.log('---------- Cleaning up, revoking object URL');

      isMounted = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [src, user, loadingUser]);

  // Filter out non-standard props before passing to <img>
  const { ...restImgProps } = imgProps;

  if (loadingUser || loading) return <>{loadingComponent}</>;
  if (error) return <>{errorComponent}</>;
  if (!objectUrl) return null;
  return (
    <img
      src={objectUrl}
      alt={imgProps.alt ?? ''}
      style={{ maxWidth: '1000px', maxHeight: '1000px', ...(imgProps.style || {}) }}
      {...restImgProps}
    />
  );
};

export default SpyderImage;

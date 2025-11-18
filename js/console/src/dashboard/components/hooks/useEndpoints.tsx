import { useState, useCallback } from 'react';
import SpyderProxy from '../../../spyder_proxy/SpyderProxy';
import { EndpointRow } from '../types/EndpointRow';

export function useEndpoints(user: any) {
    const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);
    const [endpointIdToHostnameMap, setEndpointIdToHostnameMap] = useState<Map<string, [string, boolean]>>(new Map());

    const fetchEndpoints = useCallback(async () => {
        if (!user) return;
        try {
            const endpointList = await SpyderProxy.getInstance().getAllEndpoints(user);
            setEndpointRows(endpointList);
            setEndpointIdToHostnameMap(
                new Map(endpointList.map(endpoint => [endpoint.id, [endpoint.hostname, endpoint.isOnline]]))
            );
        } catch (error) {
            console.error('Error fetching endpoints:', error);
        }
    }, [user]);

    return { endpointRows, endpointIdToHostnameMap, fetchEndpoints };
}
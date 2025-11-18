// src/proxy/SpyderServiceInterface.tsx
import { GrpcWebFetchTransport } from '../patch/grpc-web-transport';
import { SpyderServiceClient } from '../proto/portola/spyder/v1/spyder_service.client';

class SpyderServiceInterface {
    private static instance: SpyderServiceClient;

    private constructor() {}

    public static getInstance(): SpyderServiceClient {
        if (!SpyderServiceInterface.instance) {

            let baseUrl;
            if (window.location.hostname === 'localhost') {
                baseUrl = 'http://localhost:5053';
            } else {
                baseUrl = `${window.location.protocol}//${window.location.hostname.replace('console', 'spyderweb')}`;
            }

            console.log(`spyder baseUrl: ${baseUrl}`);

            const transport = new GrpcWebFetchTransport({
                baseUrl: baseUrl,
                format: 'binary',
            });
            SpyderServiceInterface.instance = new SpyderServiceClient(transport);
        }
        return SpyderServiceInterface.instance;
    }
}

export default SpyderServiceInterface;

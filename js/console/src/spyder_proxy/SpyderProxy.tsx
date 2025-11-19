import SpyderServiceInterface from "./SpyderServiceInterface";
import {User} from 'firebase/auth';
import {SpyderServiceClient} from "../proto/portola/spyder/v1/spyder_service.client";
import {
    CreateStripeCheckoutSessionRequest,
    AuthDataRequest,
} from "../proto/portola/spyder/v1/spyder_service";
import {RedemptionCode} from "../dashboard/components/types/RedemptionCode";


class SpyderProxy {
    private static instance: SpyderProxy;

    private client: SpyderServiceClient = SpyderServiceInterface.getInstance();

    // Private constructor to prevent direct instantiation
    private constructor() {
        // Initialization code here
    }

    // Static method to get the single instance of the class
    public static getInstance(): SpyderProxy {
        if (!SpyderProxy.instance) {
            SpyderProxy.instance = new SpyderProxy();
        }
        return SpyderProxy.instance;
    }

    private async getAccessToken(auth: User): Promise<string> {
        if (auth && typeof auth.getIdToken === 'function') {
            return new Promise((resolve, reject) => {
                auth.getIdToken(false)
                    .then((idToken) => {
                        resolve(idToken);
                    })
                    .catch((error) => {
                        reject(error);
                    });
            });
        } else {
            throw new Error("Invalid auth object. Expected a User instance.");
        }
    }

    public async putAuthData(user: User, codeChallenge: string): Promise<RedemptionCode> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const authDataRequest: AuthDataRequest = AuthDataRequest.create({
            accessToken: accessToken,
            refreshToken: user.refreshToken,
            codeChallenge: codeChallenge,
        });

        console.log("Putting auth data with request: ", authDataRequest);

        return await this.client.putAuthData(authDataRequest, {meta: metadata})
            .then(async (unaryCall) => {
                const code = unaryCall.response.code;

                console.log("Auth data put successfully");

                return {
                    code: code,
                };
            })
            .catch((error) => {
                console.error("Error putting auth data: ", error.message);
                return {
                    code: "",
                }
            });
    }

    public async createStripeCheckoutSession(user: User, lookupKey: string): Promise<string> {
        console.log("Creating Stripe Checkout Session for tunnelId: ", lookupKey);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createSessionRequest = CreateStripeCheckoutSessionRequest.create({
            lookupKey: lookupKey,
        });

        return await this.client.createStripeCheckoutSession(createSessionRequest, {meta: metadata})
            .then((unaryCall) => {
                const sessionUrl = unaryCall.response.url;

                console.log("Stripe Checkout Session created: ", sessionUrl);

                return sessionUrl;
            })
            .catch((error) => {
                console.error("Error creating Stripe Checkout Session: ", error.message);
                throw error;
            });
    }
}

export default SpyderProxy;

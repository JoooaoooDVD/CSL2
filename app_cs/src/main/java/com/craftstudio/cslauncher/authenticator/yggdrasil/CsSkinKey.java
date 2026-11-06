package com.craftstudio.cslauncher.authenticator.yggdrasil;

import android.util.Base64;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * The texture-signing key shared by every CS Launcher Plus install.
 *
 * <p>authlib-injector only accepts a {@code textures} property whose signature verifies against the
 * public key its own session server publishes. Skins travel between players through the game
 * server (the host answers {@code hasJoined} for guests, the guests' clients receive the host's
 * player profile), so a profile signed on one phone must verify on every other phone — which is
 * only possible when all launchers sign with the same key. The key protects nothing secret: it
 * merely tells the client "this texture property was produced by CS Launcher Plus".
 */
final class CsSkinKey {
    private CsSkinKey() {}

    private static final String PRIVATE_PKCS8 = ""
            + "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDC0blx2d6kbI99niYO6oPURcYN7j1kNx/O6GWhZ5bYWfZY"
            + "s8l21mCRE4KZkR+446AMSlNIRoywpWPzOaMgpkvDvpYukEUZA7cRcVqwxL8PfhrjWRhpThE8VNQ4T5J/smIi6A+lgWf90iXR"
            + "yXrG57boC1XKwqUl2vL9SPV8MCiIWkQs0mv6xkpegx+rC/MJh7StprWCZRQdXwozLiHwxi6MVhC/uN/KAewt2ZWOV+r+UDI3"
            + "PgWsa9XpM+i2VsKj+dG9cIildQySMI5RgIsdRnpPX/d2kuISaVjtP9vRVeS/iN2JT4b6VJvRDI29lqMx6e4aTy+IQWG0Sb7S"
            + "54TjAzglAgMBAAECggEAAj2awig1ExOLj/CMdAH2+69EwJ7Dln46z6ylFJbmKX1ZY7pHoCYFjM2aZC1C3ePCULnfHNbw4cw5"
            + "KtA4I9U9zFXKEOWIYeoExj7VQfC1WNaEtSYubXGgKZ85JJ+nqe/RiKC+I1NqcIUk+bV1Q8ng9OjCdxdHgniy8D5RUnkUUkMg"
            + "XRjLWh7pIH+6BIAnKpIBn2yd7C7N8gbMrtBSqYDufYN1/zQtINAu13RZWHi9qtXKfZzEgr+35Do32dHu3F0q2NtR4GL+/HZ0"
            + "khwNddnURmkbKdfFs3jqJGMMqpLz3QsjWqzyCGe/nCxt5rqYZVN0fJvPLDL+tvzwDySo3aR4GQKBgQDuuIvIFmfvxdOrV823"
            + "O7mAW0CXGQi/bPKqa1dhZn976dsYirl7F+rWQT7sc6XIXV20YljYZ3nwIa5YHqP8ciN//TNklwq95eYoyaBa62cIk1CTqPQe"
            + "5XHjFTyQSm03IOHFlPgVTgx0SxGy91DCTG+xHKWaJIWWtoUow6St1bzFOQKBgQDQ67BedyvrOg/R/L9+Ni9GQt02gj8p3hsh"
            + "VYy4RyqYVOPpzS4mdgn1BJGO7aoxCZGrdeEk8VEeF6NtFnSk+rjIyO2AiWgO1epwNQw7b4DQKBq+FB3FYpb3DrbGLaEfDygF"
            + "FUTyszvWYIWR9DW0F16fH7H6mRWSG7Bcoj+yA5EWTQKBgGFeXo1XLj+rVgG+R4ckUi5bigXXTS0fF/+Q1sxAjWESaq4g7zSY"
            + "9c08QZkaNpV6F51au9hQpHjglAcXQFXYjEhJE2xE+zmnivA6BTDwcU8bHzOLJz9u4Lv5zoEgCloBDHWVpHa0JfpNIEP6AjOu"
            + "D6STBazQlrh5a+jLm+DXnC5pAoGAFF+XkG7Aqqy71Dd4mR9BlvF4TxN+g1+0d5i3I1VVUwucOjWXFYsXR3yNkUmd+jA7s2PI"
            + "c+jQh0W42y+AaoJEuNfXSVyc+C1mMlEKA1XJUwNGMFkX2iLWI/ucWVEj0DqTJYPAeIaABFTfUa9l+Uz6HjxiB9Ub5Gas2Su9"
            + "WGe+o1UCgYBw2tvySC15lx3dca7iQjB03cvkdLS4d4A23/wH9KqQxDaZjzDwJyCnl3srZ4znOS273Oh4oQkSZUgLQYhsTXuw"
            + "ONKe1zuwkUP7Yq17PnfKzX0ef3xFpvfsUOTOKsS6FUDdfc1caFwi3mCk1lb60cBCoYyuP0XhAL2h8KmxKK+P5Q==";

    private static final String PUBLIC_X509 = ""
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwtG5cdnepGyPfZ4mDuqD1EXGDe49ZDcfzuhloWeW2Fn2WLPJdtZg"
            + "kROCmZEfuOOgDEpTSEaMsKVj8zmjIKZLw76WLpBFGQO3EXFasMS/D34a41kYaU4RPFTUOE+Sf7JiIugPpYFn/dIl0cl6xue2"
            + "6AtVysKlJdry/Uj1fDAoiFpELNJr+sZKXoMfqwvzCYe0raa1gmUUHV8KMy4h8MYujFYQv7jfygHsLdmVjlfq/lAyNz4FrGvV"
            + "6TPotlbCo/nRvXCIpXUMkjCOUYCLHUZ6T1/3dpLiEmlY7T/b0VXkv4jdiU+G+lSb0QyNvZajMenuGk8viEFhtEm+0ueE4wM4"
            + "JQIDAQAB";

    @NonNull
    static KeyPair load() throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(PRIVATE_PKCS8, Base64.DEFAULT)));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_X509, Base64.DEFAULT)));
        return new KeyPair(publicKey, privateKey);
    }
}

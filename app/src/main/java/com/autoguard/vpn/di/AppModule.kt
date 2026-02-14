package com.autoguard.vpn.di

import android.content.Context
import com.autoguard.vpn.data.repository.ServerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Application dependency injection module
 * Provides instances for core dependencies like network, repository, etc.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Provides OkHttpClient instance
     * Configured with logging interceptor and timeout settings
     */
    @Provides
    @Singleton
    @DefaultClient
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Allow HTTP to HTTPS redirects
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Provides server repository instance
     */
    @Provides
    @Singleton
    fun provideServerRepository(
        @ApplicationContext context: Context,
        @DefaultClient okHttpClient: OkHttpClient
    ): ServerRepository {
        return ServerRepository(context, okHttpClient)
    }
}

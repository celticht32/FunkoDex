package com.funkodex.di

import android.content.Context
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.repository.CategoryPreferenceRepository
import com.funkodex.security.SecureKeyStore
import com.funkodex.network.ConnectivityObserver
import com.funkodex.data.repository.ImageBlobRepository
import com.funkodex.network.PriceService
import com.funkodex.data.repository.PhotoRepository
import com.funkodex.data.repository.AlertRepository
import com.funkodex.data.repository.ContributionRepository
import com.funkodex.security.HmacKeyStore
import com.funkodex.ui.screens.settings.UserPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)   // SEC-D: community upload on slow connections
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

    @Provides @Singleton
    fun provideFunkoDexDatabase(@ApplicationContext context: Context): FunkoDexDatabase =
        FunkoDexDatabase(context).also { it.ensureIndexes() }

    @Provides @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferencesRepository =
        UserPreferencesRepository(context)

    @Provides @Singleton
    fun provideCategoryPreferenceRepository(db: FunkoDexDatabase): CategoryPreferenceRepository =
        CategoryPreferenceRepository(db)

    @Provides @Singleton
    fun provideSecureKeyStore(@ApplicationContext context: Context): SecureKeyStore =
        SecureKeyStore(context)

    @Provides @Singleton
    fun provideConnectivityObserver(
        @ApplicationContext context: Context,
        db: FunkoDexDatabase,
        lookup: com.funkodex.network.FunkoLookupService,
    ): ConnectivityObserver = ConnectivityObserver(context, db, lookup)

    @Provides @Singleton
    fun provideImageBlobRepository(
        db: FunkoDexDatabase,
        client: OkHttpClient,
    ): ImageBlobRepository = ImageBlobRepository(db, client)

    @Provides @Singleton
    fun providePriceService(
        client: OkHttpClient,
        secureKeyStore: SecureKeyStore,
    ): PriceService = PriceService(client, secureKeyStore)

    @Provides @Singleton
    fun providePhotoRepository(
        @ApplicationContext context: Context,
        db: FunkoDexDatabase,
    ): PhotoRepository = PhotoRepository(context, db)

    @Provides @Singleton
    fun provideAlertRepository(db: FunkoDexDatabase): AlertRepository =
        AlertRepository(db)

    @Provides @Singleton
    fun provideContributionRepository(db: FunkoDexDatabase): ContributionRepository =
        ContributionRepository(db)

    @Provides @Singleton
    fun provideHmacKeyStore(
        secureKeyStore: SecureKeyStore,
    ): HmacKeyStore = HmacKeyStore(secureKeyStore)
}

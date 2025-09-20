package com.martin.veillebt.di

import android.content.Context
import android.content.SharedPreferences // <--- AJOUTER CET IMPORT
import androidx.room.Room
import com.martin.veillebt.data.local.db.AppDatabase
import com.martin.veillebt.data.local.db.BraceletDao
import com.martin.veillebt.data.repository.BraceletRepository
import com.martin.veillebt.data.repository.BraceletRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "veille_bt_database"
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideBraceletDao(appDatabase: AppDatabase): BraceletDao {
        return appDatabase.braceletDao()
    }

    // MODIFICATION ICI: Changement pour utiliser @Binds pour le Repository
    // Si BraceletRepository est une interface et BraceletRepositoryImpl son implémentation.
    // Cela nécessite que AppModule soit une 'abstract class' ou que vous créiez un module séparé pour les @Binds.
    // Pour la simplicité, si vous gardez AppModule comme 'object', la méthode @Provides est OK.
    // Si vous voulez suivre la "meilleure pratique" pour lier des interfaces à des implémentations,
    // vous devriez utiliser @Binds dans un module abstrait.

    // OPTION A: Garder @Provides (plus simple si AppModule reste un object)
    @Provides
    @Singleton
    fun provideBraceletRepository(braceletDao: BraceletDao): BraceletRepository {
        return BraceletRepositoryImpl(braceletDao)
    }

    // --- NOUVELLES MÉTHODES POUR LES DÉPENDANCES MANQUANTES ---

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        // Choisissez un nom unique pour votre fichier de préférences
        return context.getSharedPreferences("VeilleBtAppPreferences", Context.MODE_PRIVATE)
    }

    // Pour AlarmSoundPlayer, vous avez deux options principales :
    // Option 1 : Si AlarmSoundPlayer a un constructeur simple avec @Inject
    //            et ne nécessite pas de configuration spéciale, vous n'avez pas besoin
    //            d'une méthode @Provides ici, Hilt le trouvera.
    //            Exemple pour AlarmSoundPlayer:
    //            class AlarmSoundPlayer @Inject constructor(@ApplicationContext private val context: Context) { /* ... */ }

    // Option 2 : Si AlarmSoundPlayer nécessite une logique de construction spécifique
    //            ou si c'est une interface que vous liez à une implémentation.
    //            Ici, je suppose que c'est une classe concrète qui peut être construite directement.
    //            Si elle dépend du contexte :
    // @Provides
    // @Singleton // Ou un autre scope si approprié
    // fun provideAlarmSoundPlayer(@ApplicationContext context: Context): AlarmSoundPlayer {
    //     return AlarmSoundPlayer(context) // Assurez-vous que AlarmSoundPlayer a un constructeur approprié
    // }
    // Si AlarmSoundPlayer n'a pas de dépendances ou si ses dépendances sont aussi fournies par Hilt
    // et qu'elle a un constructeur @Inject, vous n'avez RIEN à ajouter ici pour elle.

}

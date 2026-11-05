package com.xencode.feature.agent.domain.command

import dagger.Module
import dagger.Binds
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Slash command multibinding: collects every built-in [ActionCommandHandler] implementation into a Set
 * for constructor injection into [SlashCommandRegistry] (skills are read separately by the registry from SkillRepository).
 *
 * To add a built-in command, just append one more `@Binds @IntoSet` binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SlashCommandModule {

    @Binds
    @IntoSet
    abstract fun bindCompressCommandHandler(handler: CompressCommandHandler): ActionCommandHandler

    @Binds
    @IntoSet
    abstract fun bindInitCommandHandler(handler: InitCommandHandler): ActionCommandHandler

    @Binds
    @IntoSet
    abstract fun bindUsageCommandHandler(handler: UsageCommandHandler): ActionCommandHandler
}

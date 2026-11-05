package com.xencode.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xencode.feature.agent.data.local.dao.AgentMessageDao
import com.xencode.feature.agent.data.local.dao.ChatSessionDao
import com.xencode.feature.agent.data.local.dao.CheckpointDao
import com.xencode.feature.agent.data.local.dao.LlmCallRecordDao
import com.xencode.feature.agent.data.local.dao.TodoItemDao
import com.xencode.feature.agent.data.local.dao.XpEventDao
import com.xencode.feature.settings.data.local.dao.AIProviderDao
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import com.xencode.feature.agent.data.local.database.AgentDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xencode.feature.agent.data.remote.anthropic.AnthropicApi
import com.xencode.feature.agent.data.remote.gemini.GeminiApi
import com.xencode.feature.agent.data.remote.openai.OpenAIApi
import com.xencode.feature.agent.domain.container.CommandEngine
import com.xencode.feature.agent.domain.container.DelegatingCommandEngine
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.agent.domain.container.RemoteSshEngine
import com.xencode.feature.agent.domain.container.SharedPrefsSshHostKeyStore
import com.xencode.feature.agent.domain.container.SharedPrefsSshLoginKeyStore
import com.xencode.feature.agent.domain.container.SshHostKeyStore
import com.xencode.feature.agent.domain.container.SshLoginKeyStore
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.agent.domain.tool.file.ReadFileTool
import com.xencode.feature.agent.domain.tool.file.SendFileTool
import com.xencode.feature.agent.domain.tool.file.GenerateImageTool
import com.xencode.feature.agent.domain.tool.file.ViewImageTool
import com.xencode.feature.agent.domain.tool.file.WriteFileTool
import com.xencode.feature.agent.domain.tool.editor.EditFileTool
import com.xencode.feature.agent.domain.tool.container.ExecuteCommandTool
import com.xencode.feature.agent.domain.tool.container.TerminalSessionTool
import com.xencode.feature.agent.domain.tool.explorer.ListFilesTool
import com.xencode.feature.agent.domain.tool.explorer.SearchCodeTool
import com.xencode.feature.agent.domain.tool.shizuku.ShizukuTool
import com.xencode.feature.agent.domain.tool.skill.LoadSkillTool
import com.xencode.feature.agent.domain.tool.question.AskUserQuestionTool
import com.xencode.feature.agent.domain.tool.todo.TodoTool
import com.xencode.feature.agent.domain.tool.subagent.TaskTool
import com.xencode.feature.agent.domain.tool.subagent.MessageParentTool
import com.xencode.feature.agent.domain.subagent.SubAgentEventBus
import com.xencode.feature.agent.domain.prompt.SystemPromptProvider
import com.xencode.feature.agent.domain.workflow.AgentWorkflow
import com.xencode.feature.agent.domain.tool.ToolPermissionManager
import com.xencode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.xencode.feature.agent.domain.tool.ToolRegistry
import com.xencode.feature.agent.domain.tool.ToolInvoker
import com.xencode.feature.settings.data.remote.ModelMetadataService
import com.xencode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.xencode.feature.terminal.domain.RemoteTerminalSessionManager
import com.xencode.feature.terminal.domain.TerminalSessionManager
import com.xencode.feature.terminal.domain.TerminalSessionProvider
import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.DelegatingFileAccess
import com.xencode.feature.workspace.domain.LocalFileAccess
import com.xencode.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

import com.xencode.core.db.MigrationLoader
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.checkpoint.CheckpointManager
import com.xencode.feature.agent.domain.notification.AgentEventInjector
import com.xencode.feature.agent.domain.notification.AgentNotificationCenter
import com.xencode.feature.agent.domain.notification.DefaultAgentEventInjector
import com.xencode.feature.agent.domain.session.MessagePersistenceUseCase
import com.xencode.feature.agent.domain.session.SessionUseCase
import com.xencode.feature.agent.domain.tool.mcp.ManageMcpTool
import com.xencode.feature.agent.domain.tool.memory.MemoryTool
import com.xencode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.xencode.feature.agent.domain.tool.mode.PlanModeTool
import com.xencode.feature.agent.domain.tool.search.WebFetchTool
import com.xencode.feature.agent.domain.tool.search.WebSearchTool
import com.xencode.feature.agent.domain.tool.browser.BrowserTool
import com.xencode.feature.agent.domain.tool.github.GitHubTool
import com.xencode.feature.agent.domain.tool.github.GitHubWriteTool
import com.xencode.feature.agent.domain.tool.instabox.InstaboxTool
import com.xencode.feature.agent.domain.tool.renderlayout.RenderLayoutTool
import com.xencode.feature.agent.domain.workflow.ContextCompactor
import com.xencode.feature.agent.domain.workflow.StatefulAgentWorkflow
import com.xencode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.xencode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import com.xencode.feature.settings.data.repository.LanguageSettingsRepository
import com.xencode.feature.settings.data.repository.ProviderKeyRotator
import com.xencode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.xencode.feature.settings.data.repository.TitleModelSettingsRepository
import com.xencode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.xencode.feature.workspace.data.repository.WorkspaceRepository

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            AgentDatabase.DATABASE_NAME
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao {
        return database.agentMessageDao()
    }

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    @Singleton
    fun provideAIProviderDao(database: AgentDatabase): AIProviderDao {
        return database.aiProviderDao()
    }

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: AgentDatabase): RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideLlmCallRecordDao(database: AgentDatabase): LlmCallRecordDao {
        return database.llmCallRecordDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideXpEventDao(database: AgentDatabase): XpEventDao {
        return database.xpEventDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        // Unify the UA to xencode/<version> (Android); do not override when the request already carries an explicit UA (e.g. a user-defined header).
        // The version comes from PackageManager (the project does not enable BuildConfig); dev builds are 1.x.y-dev.N+hash, naturally traceable.
        val userAgent = runCatching {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            "xencode/$name (Android)"
        }.getOrDefault("xencode (Android)")
        // With streaming SSE the read timeout caps the wait between adjacent data chunks; set it to 0 (no limit)
        // so slow generations are not cut off by inter-chunk timeouts; a stall before the first byte is caught by the upstream watchdog (RetryPolicy).
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .eventListenerFactory(EventListener.Factory { call ->
                object : EventListener() {
                    private val callId = Integer.toHexString(System.identityHashCode(call))
                    private val host = call.request().url.host
                    private var callStartNanos = 0L
                    private var connectStartNanos = 0L
                    private var connectionId = "none"
                    private var protocol = "unknown"

                    private fun logStage(stage: String, details: String = "") {
                        val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callStartNanos)
                        FileLogger.i(
                            "OkHttp",
                            "call=$callId host=$host connection=$connectionId protocol=$protocol " +
                                "stage=$stage totalMs=$totalMs$details"
                        )
                    }

                    override fun callStart(call: Call) {
                        callStartNanos = System.nanoTime()
                        logStage("callStart")
                    }

                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        connectStartNanos = System.nanoTime()
                        connectionId = "none"
                        protocol = "unknown"
                    }

                    override fun connectEnd(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                        protocol: Protocol?
                    ) {
                        this.protocol = protocol?.toString() ?: "unknown"
                        val connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
                        logStage("connectEnd", " connectMs=$connectMs")
                    }

                    override fun connectFailed(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                        protocol: Protocol?,
                        ioe: IOException
                    ) {
                        this.protocol = protocol?.toString() ?: "unknown"
                        val connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
                        logStage("connectFailed", " connectMs=$connectMs exception=${ioe.javaClass.name}")
                    }

                    override fun connectionAcquired(call: Call, connection: Connection) {
                        connectionId = Integer.toHexString(System.identityHashCode(connection))
                        protocol = connection.protocol().toString()
                        logStage("connectionAcquired")
                    }

                    override fun requestHeadersEnd(call: Call, request: Request) {
                        logStage("requestHeadersEnd")
                    }

                    override fun responseHeadersEnd(call: Call, response: Response) {
                        logStage("responseHeadersEnd")
                    }

                    override fun callFailed(call: Call, ioe: IOException) {
                        logStage("callFailed", " exception=${ioe.javaClass.name}")
                    }

                    override fun callEnd(call: Call) {
                        logStage("callEnd")
                    }
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                val finalRequest = if (request.header("User-Agent") != null) {
                    request
                } else {
                    request.newBuilder().header("User-Agent", userAgent).build()
                }
                chain.proceed(finalRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    @Named("OpenAI")
    fun provideOpenAIRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("Anthropic")
    fun provideAnthropicRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAIApi(@Named("OpenAI") retrofit: Retrofit): OpenAIApi {
        return retrofit.create(OpenAIApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("Anthropic") retrofit: Retrofit): AnthropicApi {
        return retrofit.create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    @Named("Gemini")
    fun provideGeminiRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): GeminiApi {
        return retrofit.create(GeminiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCommandEngine(delegate: DelegatingCommandEngine): CommandEngine = delegate

    @Provides
    @Singleton
    fun provideFileAccess(delegate: DelegatingFileAccess): FileAccessProvider = delegate

    @Provides
    @Singleton
    fun provideTerminalSessionProvider(delegate: DelegatingTerminalSessionProvider): TerminalSessionProvider = delegate

    @Provides
    @Singleton
    fun provideDelegatingTerminalSessionProvider(
        modeHolder: ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: WorkspaceRepository
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository)

    @Provides
    @Singleton
    fun provideSshHostKeyStore(impl: SharedPrefsSshHostKeyStore): SshHostKeyStore = impl

    @Provides
    @Singleton
    fun provideSshLoginKeyStore(impl: SharedPrefsSshLoginKeyStore): SshLoginKeyStore = impl

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        generateImageTool: GenerateImageTool,
        executeCommandTool: ExecuteCommandTool,
        shizukuTool: ShizukuTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: ManageMcpTool,
        webSearchTool: WebSearchTool,
        webFetchTool: WebFetchTool,
        planModeTool: PlanModeTool,
        todoTool: TodoTool,
        memoryTool: MemoryTool,
        taskTool: TaskTool,
        messageParentTool: MessageParentTool,
        browserTool: BrowserTool,
        githubTool: GitHubTool,
        githubWriteTool: GitHubWriteTool,
        instaboxTool: InstaboxTool,
        renderLayoutTool: RenderLayoutTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            // loadSkill is moved up to right after the file tools group and before Bash: registration order is the tool schema order,
            // and models are more sensitive to tools near the front of the list (local testing: loadSkill formerly at position 12 got 0 hits in 239 calls).
            register("loadSkill", loadSkillTool)
            register("generateImage", generateImageTool)
            register("Bash", executeCommandTool)
            register("Shizuku", shizukuTool)
            register("terminal", terminalSessionTool)
            register("list", listFilesTool)
            register("search", searchCodeTool)
            register("askUserQuestion", askUserQuestionTool)
            register("manageMcp", manageMcpTool)
            register("websearch", webSearchTool)
            register("webfetch", webFetchTool)
            register("planMode", planModeTool)
            register("todo", todoTool)
            register("memory", memoryTool)
            register("task", taskTool)
            register("messageParent", messageParentTool)
            register("browser", browserTool)
            // GitHub tools go last: lower call frequency than built-ins, and registration
            // order is schema order, so they do not crowd the front of the tool list.
            register("github", githubTool)
            register("githubWrite", githubWriteTool)
            register("linux_machine", instaboxTool)
            // renderLayout goes last: niche preview tool, keep it out of the hot schema zone.
            register("renderLayout", renderLayoutTool)
        }
    }

    @Provides
    @Singleton
    fun provideAgentEventInjector(): AgentEventInjector = DefaultAgentEventInjector()

    @Provides
    @Singleton
    fun provideAgentWorkflow(
        toolRegistry: ToolRegistry,
        toolInvoker: ToolInvoker,
        aiProviderRepository: AIProviderRepository,
        openAIApi: OpenAIApi,
        anthropicApi: AnthropicApi,
        geminiApi: GeminiApi,
        promptProvider: SystemPromptProvider,
        permissionManager: ToolPermissionManager,
        policyEngine: ToolPermissionPolicyEngine,
        contextCompactor: ContextCompactor,
        planApprovalManager: PlanApprovalManager,
        modelMetadataService: ModelMetadataService,
        compactionModelSettingsRepository: CompactionModelSettingsRepository,
        titleModelSettingsRepository: TitleModelSettingsRepository,
        reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
        languageSettingsRepository: LanguageSettingsRepository,
        defaultModelSettingsRepository: DefaultModelSettingsRepository,
        generalSettingsRepository: GeneralSettingsRepository,
        sessionUseCase: SessionUseCase,
        messagePersistenceUseCase: MessagePersistenceUseCase,
        checkpointManager: CheckpointManager,
        llmCallRecordDao: LlmCallRecordDao,
        keyRotator: ProviderKeyRotator,
        agentNotificationCenter: AgentNotificationCenter,
        eventInjector: AgentEventInjector,
        fileAccess: FileAccessProvider
    ): AgentWorkflow {
        return StatefulAgentWorkflow(
            toolRegistry,
            toolInvoker,
            aiProviderRepository,
            openAIApi,
            anthropicApi,
            geminiApi,
            promptProvider,
            permissionManager,
            policyEngine,
            contextCompactor,
            planApprovalManager,
            modelMetadataService,
            compactionModelSettingsRepository,
            titleModelSettingsRepository,
            reviewerModelSettingsRepository,
            languageSettingsRepository,
            defaultModelSettingsRepository,
            generalSettingsRepository,
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager,
            llmCallRecordDao,
            keyRotator,
            agentNotificationCenter,
            eventInjector,
            fileAccess
        )
    }
}

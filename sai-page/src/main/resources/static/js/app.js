/* ============================================================
   云枢 · AI 协作工作台 — 前端交互（Alpine.js 响应式 + Thymeleaf 服务端渲染）
   说明：
   - 全局 app() 作为 Alpine 根组件（x-data="app()"），持有全部响应式状态。
   - 工作台各视图由 Thymeleaf 在 index.html 中预渲染，Alpine 按 mode/current 以
     x-show 切换可见性；所有按钮在页面加载（Alpine 初始化）时即完成绑定，
     不依赖 HTMX 注入后的二次初始化，避免“片段内按钮无响应”的问题。
   - 所有数据均来自 SAI 后端（同一 Spring Boot 应用，同源端口 80）：
       · 登录        POST /api/auth/login
       · 会话列表    GET  /api/conversation/list
       · 新建会话    POST /api/conversation
       · 会话消息    GET  /api/conversation/{id}/messages
       · 对话（流式） POST /agent/chat  (multipart + SSE)
       · 知识库      GET  /api/knowledge/page
       · 定时任务    GET  /api/schedule/page、GET /api/schedule/logs
     后端统一返回 Result{code,message,data}，api() 自动解开 data 并校验鉴权。
   ============================================================ */

function app() {
  return {
    /* ---------- 鉴权态 ---------- */
    authenticated: false,
    view: 'welcome',                 // welcome | login（仅未登录时有效）
    user: { name: 'Admin', role: '管理员' },
    username: '',

    /* ---------- 布局 / 模式 ---------- */
    mode: 'chat',                    // chat（AI 对话）| manage（协作空间）
    sidebarOpen: false,
    userMenuOpen: false,
    current: '',                     // 当前工作台视图名（协作空间默认落在 home 首页）

    /* ---------- 协作空间：仪表盘（首页） ---------- */
    dash: { model: '', todayTokens: 0, totalTokens: 0,
            knowledgeTotal: 0, knowledgeChunks: 0,
            scheduleToday: 0, scheduleTotal: 0, scheduleSuccess: 0,
            emailInbox: 0, emailSent: 0, emailUnsummarized: 0, emailToday: 0,
            todoTotal: 0, todoActive: 0, todoToday: 0,
            series: [],
            knowledgeRecent: [], scheduleRecent: [], emailRecent: [], todoRecent: [] },
    dashLoading: false,

    /* ---------- AI 对话：会话与消息（全部来自后端，无演示数据） ---------- */
    conversations: [],               // {id,name,time,prev}
    activeConv: '',
    activeConvName: '',
    messages: [],                    // {id,role:'user'|'bot',text}
    draft: '',
    sending: false,
    chatStick: true,                  // 对话区是否“贴底跟随”滚动（用户主动上滚查看历史时置 false）

    /* ---------- 计划模式（后端 /agent/plan/*，AgentScope Plan Mode） ---------- */
    planMode: false,                  // 是否已开启计划模式（进入后保持 true 直到退出/批准）
    planPhase: '',                    // NO_SESSION | EXITED | PLANNING | AWAITING_APPROVAL
    planText: '',                     // PLAN.md 内容（Markdown）
    planFile: '',                     // 计划文件路径
    planPolling: false,               // 是否正在轮询状态

    /* ---------- 编排模式（SAA，后端 /agent/chat + saaMode 标志） ---------- */
    saaMode: false,                   // 是否已开启 SAA 编排模式（每次对话请求携带 saaMode 标志）
    saaPipeline: [],                  // SAA 流水线进度：{node,label,text,started}；started=true 为「进行中」占位

    /* ---------- 知识库（来自后端 /api/knowledge） ---------- */
    kbDocs: [],
    kbKeyword: '',
    kbMode: 'kw',                   // 搜索模式（工具栏滑块开关切换）：'kw'=关键词模糊搜索（正文/关键词 LIKE，可分页）/'sem'=语义检索（向量召回 topK，按相似度排序）
    kbSearchKw: '',                 // 已生效的关键词（空=未按关键词过滤，列表为全量分页）
    kbLoading: false,
    kbLoaded: false,
    kbPage: 1,
    kbPageSize: 10,
    kbTotal: 0,
    kbPageCount: 1,
    kbSemanticText: '',
    kbSemanticActive: false,
    kbSemanticResults: [],
    kbThreshold: 0.6,               // 相似度阈值(0~1)，由前端指定并随检索请求传递（知识库以关键词建索引，余弦分天然偏低，勿设过高）
    kbDetailOpen: false,
    kbDetail: {},
    kbKwLimit: 3,                   // 列表里每条最多展示几个关键词，其余折叠为 +N（完整列表在详情弹窗里看）

    /* ---------- 定时任务（后端 /api/schedule） ---------- */
    schedTasks: [],
    schedKeyword: '',
    schedLoading: false,
    schedLoaded: false,
    schedPage: 1,
    schedPageSize: 10,
    schedTotal: 0,
    schedPageCount: 1,
    schedModalOpen: false,
    schedForm: { id: '', name: '', groupName: 'DEFAULT', description: '', cron: '', invokeTarget: '', concurrent: 0, timeout: 600, status: 1, remark: '' },
    schedBeans: {},

    /* ---------- Agent 定时任务（后端 /api/agent-schedule，AGENT 分组） ---------- */
    agentSchedTasks: [],
    agentSchedKeyword: '',
    agentSchedLoading: false,
    agentSchedLoaded: false,
    agentSchedPage: 1,
    agentSchedPageSize: 10,
    agentSchedTotal: 0,
    agentSchedPageCount: 1,
    agentSchedModalOpen: false,
    agentSchedTab: 'edit',
    agentSchedForm: { id: '', name: '', description: '', cron: '', message: '', status: 1 },

    /* ---------- 邮件（后端 /api/email，收发统一归档） ---------- */
    emailList: [],
    emailKeyword: '',
    todoList: [],
    todoLoading: false,
    todoPage: 1,
    todoPageSize: 20,
    todoPageCount: 1,
    todoTotal: 0,
    todoActive: 0,                   // 全局未完成数（后端返回，不受筛选影响）
    todoHint: '',                    // 页面头部提示文案（随筛选状态变化）
    todoKeyword: '',                 // 标题模糊搜索关键词
    todoDone: '',                    // 完成状态筛选：''=全部, '0'=未完成, '1'=已完成
    todoType: '',                    // 类型筛选：''=全部，'1'~'4'=对应类型 code
    todoModalOpen: false,
    todoTab: 'edit',
    todoDetailOpen: false,           // 详情弹窗（点击列表标题打开）
    todoDetail: {},                  // 详情数据：{id,title,type,typeLabel,content,html,done,createTime,updateTime}
    /* 待办类型候选（筛选下拉与新增/编辑弹窗共用）；要增删类型只改这里即可。
       code 与后端 todo_item.type（tinyint）及 TodoType 枚举一一对应，两处必须一致 */
    todoTypes: [
      { code: 1, label: '任务' },
      { code: 2, label: '事项' },
      { code: 3, label: '会议' },
      { code: 4, label: '回信' }
    ],
    /* 表单里的类型存「字符串 code」，与下拉 option 的 DOM value 保持一致，避免 number/string 混用 */
    todoForm: { id: '', title: '', type: '1', content: '', doneChecked: false },
    emailDirection: '',              // ''=全部, '0'=接收, '1'=发送（下拉栏 option value 为字符串，后端按 create_time 倒序返回）
    emailLoading: false,
    emailLoaded: false,
    emailPage: 1,
    emailPageSize: 10,
    emailTotal: 0,
    emailPageCount: 1,
    emailFetching: false,            // 「立即抓取」进行中
    emailDetailOpen: false,
    emailDetail: {},

    /* ---------- 任务调度日志（后端 /api/schedule/logs） ---------- */
    schedLogs: [],
    schedLogLoading: false,
    schedLogLoaded: false,
    schedLogType: '',                 // ''=全部, COMMON, AGENT

    /* ---------- 站内消息 + SSE 长连接（后端 /api/notify、/api/sse/stream） ---------- */
    notifyOpen: false,
    notifies: [],                     // 消息列表（未读优先）
    unread: 0,                        // 未读数（图标徽标）
    notifyPops: [],                   // 左侧弹出提醒卡片（SSE 新消息到达时展示，3s 自动消失）
    es: null,                         // EventSource 实例，全局仅一条 SSE 连接
    esRetry: 0,                       // 连续重连次数，用于退避与告警

    /* ---------- 操作类状态（会话 / 知识库 的写操作） ---------- */
    convMenuId: '',                    // 当前展开的会话操作菜单 id
    kbModalOpen: false,
    kbTab: 'edit',
    kbForm: { id: '', content: '', remark: '', keywords: [], kwAuto: true },
    kbKwInput: '',                  // 关键词输入框（回车或按钮添加到 kbForm.keywords）
    kbKwLoading: false,
    _abort: null,                     // 对话 SSE 的 AbortController
    chatStopped: false,               // 用户是否主动点「停止」，用于收尾区分占位文案

    /* ---------- 登录表单 ---------- */
    // 不预填任何账号密码，凭证由使用者自行输入
    loginUser: '',
    loginPass: '',
    remember: true,
    loginErr: '',

    /* ---------- 通用弹窗 ---------- */
    confirmOpen: false,
    confirmTitle: '',
    confirmText: '',

    /* ---------- 轻提示（占位按钮反馈） ---------- */
    toastMsg: '',

    /* ===================== 通用工具 ===================== */
    token() { return localStorage.getItem('sai_token') || ''; },

    /* 统一请求：自动带 Bearer 令牌，解开 Result.data，非 2xx 抛错
       opts.raw = true 时返回完整 Result（{code, message, data}），业务码由调用方自行判定 */
    async api(path, opts) {
      opts = opts || {};
      const headers = Object.assign({}, opts.headers || {});
      const t = this.token();
      if (t) { headers['Authorization'] = 'Bearer ' + t; }
      const res = await fetch(path, Object.assign({}, opts, { headers: headers }));
      if (res.status === 401) { this.forceLogout('登录已失效，请重新登录'); throw new Error('未登录或登录已失效'); }
      if (!res.ok) {
        let msg = '请求失败 (' + res.status + ')';
        try { const j = await res.json(); if (j && j.message) { msg = j.message; } } catch (e) { /* ignore */ }
        throw new Error(msg);
      }
      const ct = res.headers.get('content-type') || '';
      if (ct.indexOf('application/json') >= 0) {
        const j = await res.json();
        if (opts.raw) { return j; }
        return j.data;
      }
      return null;
    },

    /* 把后端的日期串（2026-08-16 14:02:03 或 2026-08-16T14:02:03）压成 YYYY-MM-DD HH:mm:ss */
    fmtTime(s) {
      if (!s) { return '—'; }
      s = String(s);
      if (s.indexOf(' ') >= 0) { return s.split(' ')[1].slice(0, 5); }
      return s.slice(0, 10);
    },

    /* 完整日期时间（含时分秒），兼容空格与 ISO 的 T 分隔 */
    fmtDateTime(s) {
      if (!s) { return '—'; }
      s = String(s).replace('T', ' ');
      if (s.indexOf(' ') >= 0) { return s.slice(0, 19); }
      return s.slice(0, 10);
    },

    /* ===================== 生命周期 ===================== */
    init() {
      const t = localStorage.getItem('sai_token');
      const u = localStorage.getItem('sai_user');
      if (t) {
        this.authenticated = true;
        if (u) { try { const p = JSON.parse(u); this.user = { name: p.name || 'Admin', role: p.role || '管理员' }; this.username = p.username || ''; } catch (e) { /* ignore */ } }
        this.restoreView();
        this.afterLogin();
      }
      this.initNodes();
      // 点击外部关闭用户下拉 / 会话操作菜单
      const self = this;
      document.addEventListener('click', function (e) {
        if (self.userMenuOpen && !e.target.closest('#user-menu')) { self.userMenuOpen = false; }
        if (self.convMenuId && !e.target.closest('.conv-item')) { self.convMenuId = ''; }
        if (self.notifyOpen && !e.target.closest('#notify-menu')) { self.notifyOpen = false; }
      });
    },

    /* ===================== 导航 / 模式 ===================== */
    toggleSidebar() { this.sidebarOpen = !this.sidebarOpen; },

    setMode(m) {
      this.mode = m;
      this.sidebarOpen = false;
      if (m === 'chat') { this.go('chat'); }
      else { this.go('home'); }
    },

    go(name) {
      this.current = name;
      this.sidebarOpen = false;
      // 每次进入视图都重新拉取后端数据，保证数据最新
      if (name === 'home') { this.loadDashboard(); }
      if (name === 'kb') { this.loadKB(); }
      if (name === 'schedule') { this.loadSched(); }
      if (name === 'schedule-log') { this.loadSchedLogs(); }
      if (name === 'agent-schedule') { this.loadAgentSched(); }
      // 进入邮件页即回到「全部」初始态：清空方向与关键词、回第 1 页，再拉全量数据（无需手动点刷新）
      if (name === 'email') {
        this.emailDirection = '';
        this.emailKeyword = '';
        this.emailPage = 1;
        this.loadEmail();
      }
      if (name === 'todo') { this.loadTodo(); }
      this.persistView();
    },

    restoreView() {
      // 刷新 / 重开浏览器后恢复到上次停留的视图，避免跳回 AI 对话
      try {
        const raw = localStorage.getItem('sai_view');
        if (!raw) { return; }
        const v = JSON.parse(raw);
        const views = ['chat', 'home', 'kb', 'schedule', 'schedule-log', 'agent-schedule', 'email', 'todo'];
        if (v && views.indexOf(v.current) >= 0) {
          this.current = v.current;
          this.mode = (v.current === 'chat') ? 'chat' : 'manage';
        }
      } catch (e) { /* 解析失败则忽略，回落到默认视图 */ }
    },

    persistView() {
      // 记录当前停留的视图，供刷新后恢复（与 sai_token/sai_user 同生命周期）
      try {
        localStorage.setItem('sai_view', JSON.stringify({ mode: this.mode, current: this.current }));
      } catch (e) { /* 隐私模式等场景下 localStorage 不可写则忽略 */ }
    },

    afterLogin() {
      // 进入工作台：先拉会话列表，再恢复到上次停留的视图（首次则默认 AI 对话）
      this.loadConversations();
      // 建立 SSE 长连接 + 增量同步未读与断线窗口内错过的消息（刷新页面自动重建）
      this.connectSse();
      this.syncNotify();
      this.go(this.current || (this.mode === 'chat' ? 'chat' : 'home'));
    },

    /* ===================== 站内消息 + SSE（后端 /api/notify、/api/sse/stream） ===================== */
    /**
     * 建立 SSE 长连接。
     * 说明：原生 EventSource 不支持自定义请求头，令牌走 query 参数（AuthWebFilter 已支持 ?token=）；
     * 连接断开时浏览器会按服务端下发的 retry 间隔自动重连，无需手动重连逻辑。
     */
    connectSse() {
      if (this.es) { this.es.close(); this.es = null; }
      if (typeof EventSource === 'undefined') { return; }
      const t = this.token();
      if (!t) { return; }
      const self = this;
      const es = new EventSource('/api/sse/stream?token=' + encodeURIComponent(t));

      es.addEventListener('notify', function (e) {
        self.esRetry = 0;
        let vo = null;
        try { vo = JSON.parse(e.data); } catch (err) { vo = null; }
        // 面板开着时新消息直接进列表、不累计角标也不弹卡片；关闭状态收到才 +1 亮角标并左侧弹提醒
        if (!self.notifyOpen) {
          self.unread++;
          self.pushNotifyPop(vo);
        }
        if (vo) {
          // 实时收到即推进增量游标：刷新/断线重连后以此为基准向后端补偿错过的消息
          if (vo.id) { localStorage.setItem('sai.lastNotifyId', String(vo.id)); }
          self.notifies.unshift(self.toNotifyItem(vo));
          if (self.notifies.length > 50) { self.notifies = self.notifies.slice(0, 50); }
        } else {
          self.loadNotifies();          // 解析失败时兜底重新拉列表
        }
      });

      es.onopen = function () { self.esRetry = 0; };

      es.onerror = function () {
        // 连不上时浏览器会无限自动重连（拿不到 HTTP 状态码），累计失败过多则主动放弃并提示
        self.esRetry++;
        if (self.esRetry >= 10) {
          es.close();
          self.es = null;
          self.toast('消息推送连接已断开，请刷新页面重试');
        }
      };

      this.es = es;
    },

    /** 左侧弹出一条新消息提醒卡片，3s 后自动消失（最多同屏 3 条，超出丢弃最早的） */
    pushNotifyPop(vo) {
      const self = this;
      const item = {
        key: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
        id: vo && vo.id,
        type: (vo && vo.type) || 'info',
        title: (vo && vo.title) || '收到新消息',
        content: (vo && vo.content) || '',
        link: (vo && vo.link) || '',
        read: !!(vo && vo.read),
        time: (vo && vo.createTime) ? this.fmtNotifyTime(vo.createTime) : ''
      };
      // 同一条消息可能被「SSE 实时」与「刷新后 sync 补偿」各弹一次，按 id 去重只留一条
      if (item.id && this.notifyPops.some(function (p) { return p.id === item.id; })) { return; }
      this.notifyPops.unshift(item);
      if (this.notifyPops.length > 3) { this.notifyPops = this.notifyPops.slice(0, 3); }
      if (!this._popTimers) { this._popTimers = {}; }
      if (this._popTimers[item.key]) { clearTimeout(this._popTimers[item.key]); }
      this._popTimers[item.key] = setTimeout(function () { self.dismissPop(item.key); }, 3000);
    },

    /** 移除某条提醒卡片（3s 到时 / 用户点击） */
    dismissPop(key) {
      this.notifyPops = this.notifyPops.filter(function (p) { return p.key !== key; });
      if (this._popTimers) { clearTimeout(this._popTimers[key]); delete this._popTimers[key]; }
    },

    /** 点击左侧提醒卡片：标记该条已读 + 同步面板态，有 link 则跳转对应视图 */
    async openNotifyPop(p) {
      this.dismissPop(p.key);
      if (!p.read) {
        try {
          await this.api('/api/notify/read/' + p.id, { method: 'POST' });
          p.read = true;
          const mi = this.notifies.findIndex(function (n) { return n.id === p.id; });
          if (mi >= 0) { this.notifies[mi].read = true; }
          this.unread = Math.max(0, this.unread - 1);
        } catch (e) {
          console.error('[SAI] 提醒卡片标记已读失败', e);
        }
      }
      if (p.link) {
        this.notifyOpen = false;
        this.setMode('manage');
        this.go(p.link);
      }
    },

    /** 后端 VO -> 前端列表项 */
    toNotifyItem(vo) {
      return {
        id: vo.id,
        type: vo.type || 'info',
        title: vo.title || '',
        content: vo.content || '',
        link: vo.link || '',
        read: !!vo.read,
        time: this.fmtNotifyTime(vo.createTime)
      };
    },

    /** 2026-09-02T14:10:15 -> 09-02 14:10（同年消息省掉年份，列表更紧凑） */
    fmtNotifyTime(s) {
      if (!s) { return ''; }
      const t = String(s).replace('T', ' ').slice(0, 16);
      return t.length > 5 ? t.slice(5) : t;
    },

    /**
     * 刷新 / 重连后的增量同步：弥补 SSE 断线窗口期丢掉的实时提醒。
     * - 带上本地记录的已见最大消息 id（sai.lastNotifyId）作游标请求后端 /api/notify/sync；
     * - 后端返回权威未读数 count + 游标之后的新消息 missed（消息始终先落库，因此不丢）；
     * - 角标以 count 为准（覆盖实时累加值，收敛防丢防重）；
     * - 仅当"同标签页刷新回来"且窗口期确有错过消息时，补弹最近一条提醒；全新打开只校准角标不弹历史。
     */
    async syncNotify() {
      const self = this;
      const lastId = Number(localStorage.getItem('sai.lastNotifyId') || 0);
      // 同标签页刷新保留、新开页面/关闭后清空：据此区分「刷新回来」与「全新访问」
      const resuming = sessionStorage.getItem('sai.sse.synced') === '1';
      sessionStorage.setItem('sai.sse.synced', '1');
      try {
        const d = await this.api('/api/notify/sync?afterId=' + lastId) || {};
        // 角标以服务端未读数为权威（含断线窗口期落库的消息）
        this.unread = Number(d.count) || 0;
        const missed = (Array.isArray(d.missed) ? d.missed : [])
          .filter(function (n) { return n && !n.read; });
        if (missed.length) {
          // 推进游标（missed 最新在前，首条即最大 id），下次同步从这之后开始
          localStorage.setItem('sai.lastNotifyId', String(missed[0].id));
          if (resuming && lastId > 0) { this.pushNotifyPop(missed[0]); }
        }
      } catch (e) {
        console.error('[SAI] 消息增量同步失败', e);
      }
    },

    async loadNotifies() {
      try {
        const d = await this.api('/api/notify/list?limit=20') || {};
        const list = Array.isArray(d.list) ? d.list : [];
        const self = this;
        // 已读消息不再展示：列表只保留未读项，避免历史已读消息回流
        this.notifies = list
          .filter(function (n) { return !n.read; })
          .map(function (n) { return self.toNotifyItem(n); });
      } catch (e) {
        console.error('[SAI] 消息列表加载失败', e);
      }
    },

    async toggleNotify() {
      this.notifyOpen = !this.notifyOpen;
      if (!this.notifyOpen) {
        // 关闭面板：已读消息不再展示，仅保留仍处未读的新到项
        this.notifies = this.notifies.filter(function (n) { return !n.read; });
        return;
      }
      // 打开面板：先拉未读列表让内容可见，随后自动全部已读并清角标；
      // 已读项保留显示到本次关闭时再统一移出（保证用户能看到消息内容）
      await this.loadNotifies();
      try {
        await this.api('/api/notify/read-all', { method: 'POST' });
        this.notifies.forEach(function (n) { n.read = true; });
        this.unread = 0;
      } catch (e) {
        console.error('[SAI] 打开面板置已读失败', e);
      }
    },

    async readNotify(n) {
      if (!n.read) {
        try {
          await this.api('/api/notify/read/' + n.id, { method: 'POST' });
          n.read = true;
          this.unread = Math.max(0, this.unread - 1);
        } catch (e) {
          this.toast('标记已读失败：' + e.message);
        }
      }
      if (n.link) {
        this.notifyOpen = false;
        this.setMode('manage');
        this.go(n.link);
      }
    },

    async readAllNotify() {
      try {
        await this.api('/api/notify/read-all', { method: 'POST' });
        // 已读消息不再展示，全部已读后立即从列表清掉（期间新到的未读项保留）
        this.notifies = this.notifies.filter(function (n) { return !n.read; });
        this.unread = 0;
        this.toast('已全部标记为已读');
      } catch (e) {
        this.toast('操作失败：' + e.message);
      }
    },

    /* ===================== 协作空间：仪表盘（后端 /api/stats） ===================== */
    async loadDashboard() {
      // 并发保护：切换过快时忽略重复请求
      if (this.dashLoading) { return; }
      this.dashLoading = true;
      try {
        const d = await this.api('/api/stats/dashboard') || {};
        this.dash = {
          model: d.model || '未配置',
          todayTokens: d.todayTokens || 0,
          totalTokens: d.totalTokens || 0,
          knowledgeTotal: d.knowledgeTotal || 0,
          knowledgeChunks: d.knowledgeChunks || 0,
          knowledgeRecent: Array.isArray(d.knowledgeRecent) ? d.knowledgeRecent : [],
          scheduleRecent: Array.isArray(d.scheduleRecent) ? d.scheduleRecent : [],
          scheduleToday: d.scheduleToday || 0,
          scheduleTotal: d.scheduleTotal || 0,
          scheduleSuccess: d.scheduleSuccess || 0,
          emailInbox: d.emailInbox || 0,
          emailSent: d.emailSent || 0,
          emailUnsummarized: d.emailUnsummarized || 0,
          emailToday: d.emailToday || 0,
          emailRecent: Array.isArray(d.emailRecent) ? d.emailRecent : [],
          todoTotal: d.todoTotal || 0,
          todoActive: d.todoActive || 0,
          todoToday: d.todoToday || 0,
          todoRecent: Array.isArray(d.todoRecent) ? d.todoRecent : [],
          series: Array.isArray(d.series) ? d.series : []
        };
      } catch (e) {
        console.error('[SAI] 仪表盘加载失败', e);
        this.toast('仪表盘加载失败：' + e.message);
      } finally {
        this.dashLoading = false;
      }
    },

    /* ---------- 折线图坐标计算（viewBox 0 0 700 200，绘图区 x:4~696 y:16~172） ---------- */
    dashVals(key) {
      const series = this.dash && Array.isArray(this.dash.series) ? this.dash.series : [];
      return series.map(function (d) { return Number(d[key]) || 0; });
    },
    dashMax(key) {
      const vals = this.dashVals(key);
      let max = 0;
      for (let i = 0; i < vals.length; i++) { if (vals[i] > max) { max = vals[i]; } }
      return max > 0 ? max : 1;                    // 全为 0 时避免除零，线贴底
    },
    dashX(i) {
      const n = this.dashVals('tokens').length || 7;
      return n <= 1 ? 350 : 4 + i * (692 / (n - 1));
    },
    dashY(key, i) {
      const v = this.dashVals(key)[i] || 0;
      return 16 + (1 - v / this.dashMax(key)) * 156;
    },
    dashPoints(key) {
      const self = this;
      return this.dashVals(key).map(function (v, i) {
        return self.dashX(i).toFixed(1) + ',' + self.dashY(key, i).toFixed(1);
      }).join(' ');
    },
    /* ---------- 数据点与刻度：以 SVG 字符串注入（由 x-html 渲染） ---------- */
    escSvg(s) {
      return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },
    dashCls(key) {
      if (key === 'emails') { return 'email'; }
      if (key === 'schedules') { return 'sched'; }
      if (key === 'todos') { return 'todo'; }
      return 'token';
    },
    dashLabel(key) {
      if (key === 'emails') { return '邮件收发'; }
      if (key === 'schedules') { return '调度执行'; }
      if (key === 'todos') { return '待办新增'; }
      return 'Token 消耗';
    },
    dashDots(key) {
      // 输出 <circle>：数值标签由 dashValLabels() 以 HTML 叠加层渲染（避免被 SVG 缩放变小）
      const self = this;
      const series = (this.dash && Array.isArray(this.dash.series)) ? this.dash.series : [];
      return series.map(function (d, i) {
        const v = Number(d[key]) || 0;
        const cx = self.dashX(i).toFixed(1);
        const cy = self.dashY(key, i).toFixed(1);
        const tip = self.dashLabel(key) + ' ' + self.escSvg(d.date) + '：' + self.fmtNum(v);
        return '<circle class="pt pt-' + self.dashCls(key) + '" cx="' + cx + '" cy="' + cy + '" r="3.5">'
          + '<title>' + tip + '</title></circle>';
      }).join('');
    },
    /** viewBox 坐标 -> 容器百分比（viewBox: 0 0 700 200） */
    pctX(x) { return (x / 700 * 100).toFixed(3); },
    pctY(y) { return (y / 200 * 100).toFixed(3); },
    /** 数据点上方的数值标签（HTML 层，字号不随 SVG 缩放） */
    dashValLabels(key) {
      const self = this;
      const series = (this.dash && Array.isArray(this.dash.series)) ? this.dash.series : [];
      const last = series.length - 1;
      return series.map(function (d, i) {
        const v = Number(d[key]) || 0;
        if (v <= 0) { return ''; }               // 0 值不标注，减少噪声
        const cx = self.dashX(i);
        const cy = self.dashY(key, i);
        // 顶点贴近上边界时，数值改标在点下方，避免溢出卡片
        const below = cy < 46;
        // 首末点贴边时改为左/右对齐，避免超出卡片
        const edge = i === 0 ? ' chart-val-e0' : (i === last ? ' chart-val-e1' : '');
        const top = below ? self.pctY(cy + 16) : self.pctY(cy - 10);
        return '<span class="chart-val chart-val-' + self.dashCls(key) + (below ? ' chart-val-below' : '')
          + edge + '" style="left:' + self.pctX(cx) + '%;top:' + top + '%">' + self.fmtNum(v) + '</span>';
      }).join('');
    },
    dashAxis() {
      // 输出 X 轴日期刻度（MM-DD），HTML 层渲染
      const self = this;
      const series = (this.dash && Array.isArray(this.dash.series)) ? this.dash.series : [];
      const last = series.length - 1;
      return series.map(function (d, i) {
        const t = self.escSvg(String(d.date || '').slice(5));
        const edge = i === 0 ? ' chart-ax-e0' : (i === last ? ' chart-ax-e1' : '');
        return '<span class="chart-ax' + edge + '" style="left:' + self.pctX(self.dashX(i)) + '%">' + t + '</span>';
      }).join('');
    },
    fmtNum(n) {
      return Number(n || 0).toLocaleString('zh-CN');
    },
    /** 调度成功率：累计执行数 > 0 时四舍五入到整数百分比，否则显示 - */
    schedRate() {
      const total = Number(this.dash && this.dash.scheduleTotal) || 0;
      const ok = Number(this.dash && this.dash.scheduleSuccess) || 0;
      if (total <= 0) { return '-'; }
      return Math.round((ok / total) * 100) + '%';
    },
    /** 调度失败次数 = 累计 - 成功 */
    schedFail() {
      const total = Number(this.dash && this.dash.scheduleTotal) || 0;
      const ok = Number(this.dash && this.dash.scheduleSuccess) || 0;
      return Math.max(0, total - ok);
    },
    /** 首页仪表盘·待办已完成数 = 累计 - 待完成
     *  ⚠️ 名字不能叫 todoDone：待办页有同名筛选字段 todoDone，对象字面量里方法会覆盖字段，
     *  导致 loadTodo 拼出 &done=function... 这样的脏参数 → 后端 400。 */
    dashTodoDone() {
      const total = Number(this.dash && this.dash.todoTotal) || 0;
      const active = Number(this.dash && this.dash.todoActive) || 0;
      return Math.max(0, total - active);
    },

    /* ===================== 会话（后端） ===================== */
    async loadConversations() {
      try {
        const list = await this.api('/api/conversation/list') || [];
        this.conversations = (list || []).map(function (v) {
          return {
            id: v.id,
            name: v.title || '未命名会话',
            time: this.fmtTime(v.updateTime),
            prev: (v.messageCount ? v.messageCount + ' 条消息' : '暂无消息'),
            pinned: v.isPinned === 1
          };
        }.bind(this));
        this.sortConversations();
        if (this.conversations.length) {
          this.activeConv = this.conversations[0].id;
          this.activeConvName = this.conversations[0].name;
          this.selectConv(this.activeConv);
        } else {
          this.activeConv = ''; this.activeConvName = ''; this.messages = [];
        }
      } catch (e) {
        console.error('[SAI] 加载会话失败', e);
        this.conversations = []; this.activeConv = ''; this.messages = [];
      }
    },

    async selectConv(id) {
      const c = this.conversations.find(function (x) { return x.id === id; });
      if (!c) { return; }
      this.activeConv = id;
      this.activeConvName = c.name;
      this.sidebarOpen = false;
      try {
        const list = await this.api('/api/conversation/' + id + '/messages') || [];
        this.messages = (list || []).map(function (m) {
          return { id: m.id, role: (m.role === 'user' ? 'user' : 'bot'), text: m.content || '' };
        });
        this.chatStick = true;
        this.$nextTick(() => this.scrollChatToBottom());
      } catch (e) {
        console.error('[SAI] 加载消息失败', e);
        this.messages = [];
      }
    },

    async newConv() {
      try {
        const v = await this.api('/api/conversation', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title: '新对话' })
        });
        if (v && v.id) {
          const item = { id: v.id, name: v.title || '新对话', time: '刚刚', prev: '暂无消息' };
          this.conversations.unshift(item);
          this.activeConv = v.id;
          this.activeConvName = item.name;
          this.messages = [];
          return v.id;
        }
      } catch (e) {
        console.error('[SAI] 新建会话失败', e);
      }
      return '';
    },

    /* 会话排序：置顶的排最前，其余按时间倒序（这里仅按 pinned 置顶，时间已倒序） */
    sortConversations() {
      this.conversations.sort(function (a, b) { return (a.pinned === b.pinned) ? 0 : (a.pinned ? -1 : 1); });
    },

    openConvMenu(id) { this.convMenuId = (this.convMenuId === id ? '' : id); },

    async renameConv(id) {
      this.convMenuId = '';
      const c = this.conversations.find(function (x) { return x.id === id; });
      const title = window.prompt('重命名会话', c ? c.name : '');
      if (title == null) { return; }
      const t = title.trim();
      if (!t) { return; }
      try {
        await this.api('/api/conversation/rename', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: id, title: t })
        });
        if (c) { c.name = t; if (this.activeConv === id) { this.activeConvName = t; } }
        this.toast('已重命名');
      } catch (e) { this.toast('重命名失败：' + e.message); }
    },

    async togglePin(id) {
      this.convMenuId = '';
      const c = this.conversations.find(function (x) { return x.id === id; });
      if (!c) { return; }
      const next = c.pinned ? 0 : 1;
      try {
        await this.api('/api/conversation/pin', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: id, isPinned: next })
        });
        c.pinned = next === 1;
        this.sortConversations();
        this.toast(next === 1 ? '已置顶' : '已取消置顶');
      } catch (e) { this.toast('置顶失败：' + e.message); }
    },

    deleteConv(id) {
      this.convMenuId = '';
      const c = this.conversations.find(function (x) { return x.id === id; });
      const self = this;
      this.confirm('确定删除会话「' + (c ? c.name : '') + '」？', async function () {
        try {
          await self.api('/api/conversation/' + id, { method: 'DELETE' });
          self.conversations = self.conversations.filter(function (x) { return x.id !== id; });
          if (self.activeConv === id) {
            if (self.conversations.length) { self.activeConv = ''; self.selectConv(self.conversations[0].id); }
            else { self.activeConv = ''; self.activeConvName = ''; self.messages = []; }
          }
          self.toast('已删除');
        } catch (e) { self.toast('删除失败：' + e.message); }
      });
    },

    onKey(e) {
      // 输入法组合过程中（如中文拼音、英文输入法选词时按回车确认候选词）
      // 不触发发送；必须等组合结束（isComposing=false）后的回车才发送。
      // keyCode===229 是部分浏览器/输入法在组合态的统一标识，作为兜底。
      if (e.isComposing || e.keyCode === 229) { return; }
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        this.send();
      }
    },

    /* 对话区滚动跟随：流式回复时自动贴底；用户主动上滚查看历史则暂停跟随 */
    scrollChatToBottom() {
      const el = this.$refs.chatBody;
      if (!el) { return; }
      el.scrollTop = el.scrollHeight;
    },
    onChatScroll() {
      const el = this.$refs.chatBody;
      if (!el) { return; }
      // 距底部超过 48px 视为用户主动上滚，停止自动跟随；回到底部则恢复
      this.chatStick = (el.scrollHeight - el.scrollTop - el.clientHeight) < 48;
    },
    stickChat() {
      if (this.chatStick) { this.scrollChatToBottom(); }
    },

    // 输入框高度随内容/行高自动伸缩
    grow() {
      const el = this.$refs.chatText;
      const d = this.draft; // 读取 draft 让 x-effect 在其变化时重算
      if (!el) { return; }
      el.style.height = 'auto';
      const max = 96;
      const h = Math.min(el.scrollHeight, max);
      el.style.height = h + 'px';
      el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden';
    },

    // Markdown 渲染（安全：DOMPurify 清洗；库缺失时降级为纯文本转义）+ 代码块语法高亮
    md(text) {
      if (text == null) { return ''; }
      // AI 常把多行代码挤在一行（多个 # 注释标记连写），递归按 # 断行；对正常单行注释零影响
      const fixCodeNewlines = (txt) => {
        const DQ = '"', SQ = "'", BT = '`';
        const splitAtComment = (line) => {
          let i = 0, inStr = null, firstHash = -1;
          for (; i < line.length; i++) {
            const c = line[i];
            if (inStr) { if ((c === DQ || c === SQ || c === BT) && line[i - 1] !== '\\') inStr = null; continue; }
            if (c === DQ || c === SQ || c === BT) { inStr = c; continue; }
            if (c === '#') { firstHash = i; break; }
          }
          if (firstHash === -1) { return [line]; }
          const after = line.slice(firstHash + 1);
          let j = 0, inStr2 = null, hasSecond = false;
          for (; j < after.length; j++) {
            const c = after[j];
            if (inStr2) { if ((c === DQ || c === SQ || c === BT) && after[j - 1] !== '\\') inStr2 = null; continue; }
            if (c === DQ || c === SQ || c === BT) { inStr2 = c; continue; }
            if (c === '#') { hasSecond = true; break; }
          }
          if (!hasSecond) { return [line]; }
          return [line.slice(0, firstHash + 1).replace(/\s+$/, '')].concat(splitAtComment(after.trim()));
        };
        return txt.split('\n').flatMap(splitAtComment).join('\n');
      };
      // 块级标记拆段：AI 输出常把标题/列表项直接粘在前文末尾（无换行、## 后也无空格，
      // 如“##新民主主义革命”“此后为新- **领导阶级**”）。marked 仅把“行首且 ## 后带空格”
      // 的文本当作标题/列表，否则会以原始 markdown 文本展示——这正是历史消息渲染失败的现象。
      // 这里把这类粘合标记拆成独立段落（含补空格），对已是合法换行的正文零影响。
      const breakBlocks = (s) => {
        // 保护加粗区间：避免拆段正则误命中 **...** 内部的 "- " / "1. "，
        // 把加粗拦腰拆断（例："**ob- 前缀帮**" 被切成 "**ob\n\n- 前缀帮**"，
        // 导致 ** 字面泄漏、粗体失效）。思路同 protectTables：占位→拆段→还原。
        const bolds = [];
        const src = s.replace(/\*\*[^*\n]+\*\*/g, (m) => {
          bolds.push(m);
          return '\u0001B' + (bolds.length - 1) + '\u0002';
        });
        const out = src
          // 标题(##~######)：后接非空白(粘合内容)时补一个空格使其成为合法标题；
          // 排除单个 #（避免误伤 C#、#fff 等）。行首/行中通用。
          .replace(/(#{2,6})(?=[^\s#])/g, '$1 ')
          // 标题在行中非行首且已带空格(如"X## 标题")时，前置空行使其成为独立块
          .replace(/(\S)(#{2,6})(?=\s)/g, '$1\n\n$2')
          // 列表项（- / * / + 后跟空格）：前文非行首且非"汉字-汉字"粘连(en-dash,无空格)时前置空行；
          // 仅当列表内容以 *、中文、数字开头，避免误拆普通句子里的" - "。
          .replace(/(\S)(- )(?=[\*\u4e00-\u9fa5\d])/g, '$1\n\n$2')
          // 有序列表（数字. 或数字) 后跟空格）：非行首且后面紧跟中文时前置空行（排除 2026.08 等日期）。
          .replace(/(\S)(\d+[.)] )(?=[\u4e00-\u9fa5])/g, '$1\n\n$2');
        return out.replace(/\u0001B(\d+)\u0002/g, (_, n) => bolds[+n]);
      };
      // 对非代码文本应用连字符/小数标题拆段规则；行内代码用反引号对保护，避免其中出现的
      // `cmd-中文` / `1.标题` 被误拆而破坏行内代码渲染（围栏代码块不在此处处理）
      const normalizeText = (s) => s
        // 行首漏空格的列表项兜底：markdown 要求 "- "/"1. " 后留空格才识别为列表，
        // AI 常写行首 "-中文"/"1.中文"（无空格），marked 会当普通段落原样展示（字面保留 -/1.）。
        // 仅在行首补空格（^ 多行模式逐行匹配），正文中的连字符（"瑞-迪"、"「-深」"、"-ion"）不受影响。
        .replace(/^([-*+])(?=[\u4e00-\u9fa5])/gm, '$1 ')
        .replace(/^(\d+[.)])(?=[\u4e00-\u9fa5])/gm, '$1 ')
        // 内联的 **数字.标题** 前面拆成独立段落
        .replace(/([^\s\n])(\*\*\d+[\.、])/g, '$1\n\n$2')
        // 中文标点后紧跟 数字.中文标题 时拆段
        .replace(/([。；：，！？\)\]\}」』】])(\d+[\.、][\u4e00-\u9fa5])/g, '$1\n\n$2')
        // 加粗标题后紧跟 -列表项 时拆段。[^*\n] 禁止跨行：此前 [^*]+ 会从上一行的某个 **
        // 一路吞到下一行才找到闭合 **，把相邻两条列表/段落拦腰拆断（如 "- **-ion结尾**…"）。
        .replace(/(\*\*[^*\n]+\*\*)(-\S)/g, '$1\n\n$2')
        // 正文中内联的 -中文列表项 时拆段。前导限定为句读/闭合引号（句号、冒号、」』”’等），
        // 即"一句说完紧粘列表项"的形态；前导若是汉字（"瑞-迪"）、开引号/括号（"「-深」"）、
        // 星号（加粗边界）等正文连字符场景一律不拆，避免把正文短语误判成列表强行换行。
        .replace(/([。！？；：，）」』”’])(-[\u4e00-\u9fa5])/g, '$1\n\n$2');
      const normalizeMd = (txt) => {
        // GFM 表格块（含表头分隔行、以 | 开头的连续行组）识别：分隔行如 | --- | 或 |:---:|
        const isTableSepRow = (l) => /^\s*\|[\s:|-]+\|\s*$/.test(l);
        const tables = [];
        // 将表格整块抽离为占位符：拆段正则（breakBlocks/normalizeText）若命中表格行内文本
        // （如音标列里的 "ob-盖"、"**加粗**-x"、"1. 中文"），会在行内插入空行把表格拦腰切断，
        // 后半截退化成普通段落/列表——这是此前英文词表等长表格"前半正常、后半掉出表外"的根因。
        // 表格先整体替换为占位符再走拆段逻辑，normalize 结束后原样还原，保证表格永不被打断。
        const protectTables = (seg) => {
          if (!/^\s*\|/m.test(seg)) { return seg; }
          const lines = seg.split('\n');
          const out = [];
          let i = 0;
          while (i < lines.length) {
            if (/^\s*\|/.test(lines[i])) {
              const s = i;
              while (i < lines.length && /^\s*\|/.test(lines[i])) { i++; }
              const block = lines.slice(s, i);
              if (block.some(isTableSepRow)) {
                tables.push(block.join('\n'));
                out.push('', '\u0001T' + (tables.length - 1) + '\u0002', '');
              } else {
                out.push(...block);
              }
            } else {
              out.push(lines[i]); i++;
            }
          }
          return out.join('\n');
        };
        const restoreTables = (s) => s.replace(/\u0001T(\d+)\u0002/g, (_, n) => tables[+n]);
        const parts = txt.split(/(```[\s\S]*?```)/g);
        for (let i = 0; i < parts.length; i += 2) {
          // 围栏块之外先整体保护表格块，再隔离行内代码：按反引号对切分，仅对代码外的内容规范化
          const pieces = protectTables(parts[i]).split(/(`+)([\s\S]*?)\1/g);
          let out = '';
          for (let k = 0; k < pieces.length; k += 3) {
            out += breakBlocks(normalizeText(pieces[k] || ''));
            if (pieces[k + 1]) { out += pieces[k + 1] + (pieces[k + 2] || ''); }
          }
          parts[i] = restoreTables(out);
        }
        return parts.join('');
      };
      if (window.marked && typeof marked.parse === 'function') {
        // 围栏语言标识后若紧跟代码首行（无换行），补一个换行，避免首行代码被当作语言名吞掉。
        // 注意：语言标识须按「完整标识符」匹配（负向预查 (?![a-zA-Z0-9+#-]) 防止回退到更短的别名，
        // 否则 python 会被回退匹配成别名 py 并把剩余 thon 泄漏进代码体，破坏历史消息的代码块渲染）。
        let src = String(text).replace(/(```)([a-zA-Z0-9+#-]+)(?![a-zA-Z0-9+#-])(?=\S)/g, '$1$2\n');
        // HTML 代码块抽成占位符（必须早于 normalizeMd，否则拆段正则会把标签结构打乱），
        // 渲染结束前再替换成「带样式的预览 + 可折叠源码」
        const htmlBlocks = [];
        src = src.replace(/```(html|htm|svg)[^\n]*\n([\s\S]*?)```/gi, (m, lang, code) => {
          htmlBlocks.push(code);
          return '\n\n<div data-html-slot="' + (htmlBlocks.length - 1) + '"></div>\n\n';
        });
        src = normalizeMd(src);
        let html = marked.parse(src, { gfm: true, breaks: true });
        if (window.DOMPurify && typeof DOMPurify.sanitize === 'function') {
          html = DOMPurify.sanitize(html);
        }
        if (window.hljs) {
          const tmp = document.createElement('div');
          tmp.innerHTML = html;
          const blocks = tmp.querySelectorAll('pre code');
          for (let i = 0; i < blocks.length; i++) {
            try {
              const el = blocks[i];
              const cls = (el.className || '') + ' ' + ((el.parentNode && el.parentNode.className) || '');
              // 仅对以 # 作行注释的语言（python/ruby/shell/yaml/toml/ini 等）做连写注释拆分；
              // 否则 CSS 的 #fff、JS 的私有字段 #x 等会被误拆行，破坏代码展示
              const hashComment = /(^|\s)(language-)?(python|py|ruby|rb|sh|bash|shell|zsh|yml|yaml|toml|dockerfile|ini|cfg|conf|properties)(\s|$)/i.test(cls);
              if (hashComment) { el.textContent = fixCodeNewlines(el.textContent); }
              window.hljs.highlightElement(el);
            } catch (e) { /* ignore */ }
          }
          html = tmp.innerHTML;
        }
        if (htmlBlocks.length) { html = this.renderHtmlBlocks(html, htmlBlocks); }
        return html;
      }
      const d = document.createElement('div');
      d.textContent = String(text);
      return d.innerHTML.replace(/\n/g, '<br>');
    },

    /* HTML 代码块：渲染为「带样式的预览 + 可折叠源码」。
       用 iframe（sandbox 禁脚本、allow-same-origin 仅用于父页面读高度）承载，
       既让模型输出自带样式生效，又不会污染会话页自身的 CSS。 */
    renderHtmlBlocks(html, blocks) {
      this.ensureHtmlPreviewFit();
      return html.replace(/<div data-html-slot="(\d+)"><\/div>/g, (m, n) => {
        const code = blocks[+n];
        if (code == null) { return ''; }
        const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        return '<div class="html-preview">'
          + '<div class="html-preview-bar"><span class="html-preview-tag">HTML 预览</span></div>'
          + '<iframe class="html-preview-frame" loading="lazy" sandbox="allow-same-origin" '
          + 'srcdoc="' + esc(code).replace(/"/g, '&quot;') + '"></iframe>'
          + '</div>'
          + '<details class="html-src"><summary>查看源码</summary>'
          + '<pre><code class="language-html">' + esc(code) + '</code></pre></details>';
      });
    },

    /* iframe 加载后按内容高度自适应：iframe 的 load 不冒泡，故在捕获阶段监听 */
    ensureHtmlPreviewFit() {
      if (window.__saiHtmlFit) { return; }
      window.__saiHtmlFit = true;
      window.addEventListener('load', (e) => {
        const f = e.target;
        if (!f || !f.classList || !f.classList.contains('html-preview-frame')) { return; }
        try {
          const doc = f.contentDocument;
          const h = doc && doc.documentElement ? doc.documentElement.scrollHeight : 0;
          if (h) { f.style.height = Math.min(Math.max(h + 32, 180), 1200) + 'px'; }
        } catch (err) { /* 沙箱限制时保留默认高度 */ }
      }, true);
    },

    async send() {
      const text = (this.draft || '').trim();
      if (!text || this.sending) { return; }
      // 没有会话则先建一个
      if (!this.activeConv) {
        const id = await this.newConv();
        if (!id) { return; }
      }
      const uid = Date.now();
      this.messages.push({ id: uid, role: 'user', text: text });
      const c = this.conversations.find(function (x) { return x.id === this.activeConv; }.bind(this));
      if (c) { c.prev = text; c.time = '刚刚'; }
      this.draft = '';
      this.sending = true;
      this.chatStick = true;                       // 新对话开始，恢复贴底跟随
      this.$nextTick(() => this.scrollChatToBottom());
      try {
        await this.sendStream(text);
      } catch (e) {
        this.messages.push({ id: Date.now() + 2, role: 'bot', text: '（智能体响应出错：' + (e.message || '未知错误') + '）' });
      } finally {
        this.sending = false;
      }
    },

    /* ===================== 计划模式（Plan Mode） ===================== */
    async planEnter() {
      try {
        const r = await this.api('/agent/plan/enter?conversationId=' + encodeURIComponent(this.activeConv), { method: 'POST' });
        this.planMode = true;
        if (r && r.plan) { this.planText = r.plan; }
        if (r && r.phase) { this.planPhase = r.phase; }
        if (r && r.planFile) { this.planFile = r.planFile; }
        this.messages.push({ id: Date.now(), role: 'bot', text: '✅ 已进入计划模式。我会先把任务拆解为可执行的步骤写入计划，请你审阅后再批准执行。' });
        this.pollPlanStatus();
      } catch (e) {
        this.messages.push({ id: Date.now(), role: 'bot', text: '进入计划模式失败：' + (e.message || '未知错误') });
      }
    },
    async planExit(approve) {
      try {
        await this.api('/agent/plan/exit?conversationId=' + encodeURIComponent(this.activeConv) + '&approve=' + (approve ? 'true' : 'false'), { method: 'POST' });
        this.planMode = false;
        this.planPolling = false;
        this.planPhase = 'EXITED';
        if (approve) {
          this.messages.push({ id: Date.now(), role: 'bot', text: '✅ 已批准计划，智能体开始按步骤执行。' });
        } else {
          this.planText = '';
          this.messages.push({ id: Date.now(), role: 'bot', text: '⛔ 已取消计划模式，本次规划未被执行。' });
        }
      } catch (e) {
        this.messages.push({ id: Date.now(), role: 'bot', text: (approve ? '批准' : '取消') + '计划失败：' + (e.message || '未知错误') });
      }
    },
    async togglePlan() {
      if (this.planMode) {
        await this.planExit(false);
      } else {
        this.saaMode = false;          // 互斥：进入计划模式时关闭编排模式
        await this.planEnter();
      }
    },
    /* ===================== 编排模式（SAA Mode） ===================== */
    // SAA 编排模式为「每次对话请求携带的开关标志」，无需服务端进入/退出，仅本地翻转布尔值；
    // 与「计划模式」互斥：开启 SAA 时若计划模式仍开，则先退出计划模式（含服务端清理）。
    async toggleSaa() {
      if (this.saaMode) { this.saaMode = false; return; }
      if (this.planMode) { await this.planExit(false); }
      this.saaMode = true;
    },
    async refreshPlanStatus() {
      try {
        const s = await this.api('/agent/plan/status?conversationId=' + encodeURIComponent(this.activeConv));
        if (!s) { return; }
        this.planPhase = s.phase || '';
        this.planText = s.plan || '';
        this.planFile = s.planFile || '';
        if (s.phase === 'AWAITING_APPROVAL') { this.planPolling = false; }
      } catch (e) { /* 静默：状态查询失败不中断 */ }
    },
    pollPlanStatus() {
      // 进入计划模式后周期性拉取状态，直到进入待批准或退出
      this.planPolling = true;
      const tick = () => {
        if (!this.planPolling) { return; }
        this.refreshPlanStatus().then(() => {
          if (this.planPhase === 'AWAITING_APPROVAL' || !this.planPolling) { return; }
          setTimeout(tick, 2500);
        });
      };
      setTimeout(tick, 1500);
    },

    /* 真实流式对话：POST /agent/chat（multipart + SSE） */
    async sendStream(text) {
      const controller = new AbortController();
      this._abort = controller;
      this.saaPipeline = [];           // 新一轮对话：清空 SAA 流水线进度
      const fd = new FormData();
      // 必须以 application/json 的 part 提交：Spring WebFlux 的 @RequestPart("data") ChatDTO
      // 仅由 JSON 解码器解析，若用普通字符串提交会被默认标记为 text/plain 而触发 400 / Unsupported
      // Media Type，导致 chatDTO 解析失败、对话请求被拒绝。用 Blob 显式声明 contentType 即可修复。
      const chatJson = JSON.stringify({
        conversationId: this.activeConv,
        userId: this.username || this.user.name,
        sessionId: this.activeConv,
        message: text,
        loopMode: false,
        saaMode: this.saaMode
      });
      fd.append('data', new Blob([chatJson], { type: 'application/json' }));
      const res = await fetch('/agent/chat', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + this.token() },
        body: fd,
        signal: controller.signal
      });
      if (!res.ok) {
        let msg = '对话请求失败 (' + res.status + ')';
        try { const j = await res.json(); if (j && j.message) { msg = j.message; } } catch (e) { /* ignore */ }
        throw new Error(msg);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder('utf-8');
      const botId = 'b' + Date.now();
      let buf = '';
      let firstChunk = true;
      let emptyReplyHint = '';   // 后端兜底占位文案（模型空输出时下发，仅展示不落库）
      try {
        while (true) {
          // 注意：不能用 this._abort 判断停止——它在发送一开始就持有 AbortController 实例（恒为真），
          // 否则进入循环会立即 break、一条回复都收不到，直接显示「（已停止生成）」。
          // 应检测 signal 的 abort 状态（用户点「停止」后 controller.abort() 才会变 true）。
          // 实际上 signal 已传给 fetch，abort 时 reader.read() 会直接 reject AbortError 由外层吞掉，
          // 这里再显式判断一次，便于在等待下一块前就提前跳出。
          if (controller.signal.aborted) { try { await reader.cancel(); } catch (e) { /* ignore */ } break; }
          const chunk = await reader.read();
          if (chunk.done) { break; }
          buf += decoder.decode(chunk.value, { stream: true });
          let idx;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const raw = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            const line = raw.split('\n').find(function (l) { return l.indexOf('data:') === 0; });
            if (!line) { continue; }
            const payload = line.slice(5).trim();
            if (!payload) { continue; }
            let evt;
            try { evt = JSON.parse(payload); } catch (e) { continue; }
            if (evt.message === 'END') { continue; }            // 结束标记
            const d = evt.data;
            if (d && d.reply != null) {                          // 文本分片
              if (d.emptyReply) {
                // 后端兜底占位（模型未返回内容）：仅记录标志，不把提示文本拼进气泡，
                // 待循环结束统一处理，以区分「用户主动停止」与「模型空输出」
                emptyReplyHint = d.reply;
              } else if (firstChunk) {
                this.messages.push({ id: botId, role: 'bot', text: d.reply });
                firstChunk = false;
              } else {
                const bot = this.messages.find(function (m) { return m.id === botId; });
                if (bot) { bot.text = bot.text + d.reply; }
              }
              // 流式内容增长时，若用户未主动上滚则保持贴底
              this.$nextTick(() => this.stickChat());
            }
            // SAA 编排流水线进度：每个节点先发 started=true 占位（「进行中」），再发带文本产出
            if (d && d.pipelineStep) {
              const ps = d.pipelineStep;
              const exist = this.saaPipeline.find(function (p) { return p.node === ps.node; });
              if (!exist) {
                this.saaPipeline.push({ node: ps.node, label: ps.label || ps.node, text: ps.text || '', started: !!ps.started });
              } else {
                // 节点开始占位 → 产出文本：更新进度
                exist.label = ps.label || exist.label;
                exist.text = ps.text || exist.text;
                exist.started = !!ps.started;
              }
            }
            // d.heartbeat 等保活包忽略
          }
        }
      } catch (e) {
        if (e.name !== 'AbortError') { throw e; }
      }
      const bot = this.messages.find(function (m) { return m.id === botId; });
      if (!bot) {
        // 从未收到任何真实回复内容：用户主动停止 →「已停止生成」；否则（模型空输出/流异常）给更准确的提示
        this.messages.push({ id: botId, role: 'bot',
          text: this.chatStopped ? '（已停止生成）' : (emptyReplyHint || '（智能体未返回内容，可重新发送这条消息试试）') });
      } else if (!bot.text) {
        // 收到过占位但无真实文本：同样按是否主动停止区分文案
        bot.text = this.chatStopped ? '（已停止生成）' : (emptyReplyHint || '（智能体未返回内容，可重新发送这条消息试试）');
      }
      this.chatStopped = false;
      this._abort = null;
    },

    /* 中途停止：调用 POST /agent/chat/{conversationId}/stop 并取消 SSE 读取 */
    async stopChat() {
      if (!this.activeConv) { return; }
      this.chatStopped = true;   // 标记为用户主动停止，便于 sendStream 收尾区分占位文案
      try {
        await this.api('/agent/chat/' + this.activeConv + '/stop', { method: 'POST' });
      } catch (e) { /* 忽略后端错误，前端直接中断流 */ }
      if (this._abort) { this._abort.abort(); this._abort = null; }
      this.sending = false;
    },

    /* ===================== 知识库（后端 /api/knowledge） ===================== */
    // 列表（分页）。kbSearchKw 非空时按关键词模糊搜索（后端同时匹配正文与已提炼的关键词），空则为全量
    async loadKB() {
      this.kbLoading = true;
      try {
        let url = '/api/knowledge/page?pageNum=' + this.kbPage + '&pageSize=' + this.kbPageSize;
        if (this.kbSearchKw) { url += '&keyword=' + encodeURIComponent(this.kbSearchKw); }
        const data = await this.api(url) || {};
        const list = (data && data.list) ? data.list : [];
        const total = (data && data.total != null) ? data.total : list.length;
        this.kbTotal = total;
        this.kbPageCount = Math.max(1, Math.ceil(total / this.kbPageSize));
        if (this.kbPage > this.kbPageCount) { this.kbPage = this.kbPageCount; }
        this.kbDocs = list.map(function (v) {
          return {
            id: v.id,
            name: (v.content || '').replace(/\s+/g, ' ').slice(0, 30) || '未命名',
            snippet: (v.content || '').replace(/\s+/g, ' ').slice(0, 90),
            remark: v.remark || '',
            keywords: Array.isArray(v.keywords) ? v.keywords : [],
            time: this.fmtTime(v.updateTime || v.createTime),
            indexed: true
          };
        }.bind(this));
      } catch (e) {
        console.error('[SAI] 加载知识库失败', e);
        this.kbDocs = [];
        this.toast('知识库加载失败：' + e.message);
      } finally {
        this.kbLoading = false;
      }
    },

    /* 统一搜索入口：按 kbMode 分流。
       'kw'  → 关键词模糊搜索（后端 LIKE 正文 + 已提炼关键词，支持分页，结果可预期）
       'sem' → 语义检索（向量召回 topK，按相似度排序，不分页，结果含 score）
       两种模式复用同一张表与同一套列样式，只有文字与表头随之变化。 */
    async unifiedSearch() {
      const text = (this.kbKeyword || '').trim();
      this.kbPage = 1;
      if (!text) {
        // 搜索框为空 = 退出搜索态，回到全量列表
        this.clearSemantic();
        this.kbSearchKw = '';
        await this.loadKB();
        return;
      }
      if (this.kbMode === 'kw') {
        // 关键词模糊搜索：交给后端（正文 / 关键词 LIKE），命中数即 total，走正常分页
        this.clearSemantic();
        this.kbSearchKw = text;
        await this.loadKB();
        return;
      }
      // 语义检索：清掉关键词过滤，避免两套条件互相干扰
      this.kbSearchKw = '';
      this.kbLoading = true;
      try {
        const list = await this.api('/api/knowledge/semantic?text=' + encodeURIComponent(text) + '&topK=5&threshold=' + this.kbThreshold) || [];
        const sr = (list || []).map(function (v) {
          return { baseId: v.baseId, content: v.content || '', keyword: v.keyword || '', score: v.score };
        });
        this.kbSemanticResults = sr;
        this.kbSemanticActive = true;      // 有查询即进入检索态：有命中按相关度展示，无命中展示空（均不回退全量列表）
      } catch (e) {
        this.kbSemanticResults = [];
        this.kbSemanticActive = true;        // 向量服务不可用：展示空并提示，不回退全量列表
        this.toast('语义检索失败：' + e.message);
      } finally {
        this.kbLoading = false;
      }
    },

    /* 切换「搜索 / 检索」模式：把当前搜索框里的词在新模式下重跑一遍，省得用户再点一次按钮 */
    toggleKbMode() {
      this.kbMode = (this.kbMode === 'sem') ? 'kw' : 'sem';
      this.kbPage = 1;
      if ((this.kbKeyword || '').trim()) {
        this.unifiedSearch();
      } else {
        this.clearSemantic();
        this.kbSearchKw = '';
        this.loadKB();
      }
    },

    /* 相似度阈值调整后即时重查（仅语义检索态有意义；关键词搜索不看阈值，直接忽略） */
    applyKbTh() {
      if (this.kbMode !== 'sem' || !this.kbSemanticActive) { return; }
      if (!(this.kbKeyword || '').trim()) { return; }
      this.unifiedSearch();
    },

    kbGoto(p) {
      if (p < 1 || p > this.kbPageCount) { return; }
      this.kbPage = p;
      this.loadKB();
    },

    /* 新建 / 编辑：编辑时先拉详情拿完整正文 */
    async openKbModal(item) {
      this.kbKwInput = '';
      if (item && item.id) {
        try {
          const d = await this.api('/api/knowledge/' + item.id) || {};
          // 详情接口若没返回关键词（如未重启），回退用列表行里已有的，避免编辑时把关键词清空
          let kws = Array.isArray(d.keywords) ? d.keywords : [];
          if (!kws.length && Array.isArray(item.keywords)) { kws = item.keywords; }
          this.kbForm = {
            id: d.id || item.id,
            content: d.content || '',
            remark: d.remark || '',
            keywords: kws.slice(),
            kwAuto: false                 // 编辑态：已加载现有关键词，默认由用户手动维护
          };
        } catch (e) {
          this.kbForm = { id: item.id, content: '', remark: '', keywords: [], kwAuto: false };
        }
      } else {
        // 新建态：没有关键词可维护，默认交给模型提炼
        this.kbForm = { id: '', content: '', remark: '', keywords: [], kwAuto: true };
      }
      this.kbTab = 'edit';
      this.kbModalOpen = true;
    },

    /* 关键词编辑：回车添加，支持逗号 / 顿号 / 分号 / 换行分隔，自动去重 */
    addKbKeyword() {
      const raw = (this.kbKwInput || '').trim();
      if (!raw) { return; }
      const parts = raw.split(/[,，、;；\n\r\t]+/).map(function (s) { return s.trim(); })
        .filter(function (s) { return s; });
      const seen = new Set(this.kbForm.keywords);
      let added = 0;
      const kws = this.kbForm.keywords;
      parts.forEach(function (p) {
        if (!seen.has(p)) { seen.add(p); kws.push(p); added++; }
      });
      this.kbKwInput = '';
      if (!added) { this.toast('关键词已存在'); return; }
      this.kbForm.kwAuto = false;       // 手动加过词就不再自动覆盖
    },

    removeKbKeyword(i) {
      this.kbForm.keywords.splice(i, 1);
      this.kbForm.kwAuto = false;
    },

    /* 调用后端提炼接口预览关键词（不落库），填回列表供微调 */
    async autoKbKeywords() {
      const content = (this.kbForm.content || '').trim();
      if (!content) { this.toast('请先填写正文内容'); return; }
      this.kbKwLoading = true;
      try {
        const body = { content: content };
        const list = await this.api('/api/knowledge/extract-keywords', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        }) || [];
        if (!Array.isArray(list) || !list.length) { this.toast('未提炼出关键词，可手动添加'); return; }
        this.kbForm.keywords = list.slice();
        this.kbForm.kwAuto = false;
        this.toast('已生成 ' + list.length + ' 个关键词');
      } catch (e) { this.toast('生成失败：' + e.message); }
      finally { this.kbKwLoading = false; }
    },

    async saveKb() {
      const f = this.kbForm;
      const body = { content: f.content, remark: f.remark };
      if (f.id) { body.id = f.id; }
      // kwAuto=true → 不传 keywords，交给模型提炼；否则以手动列表为准（空数组=用户明确清空）
      if (!f.kwAuto) { body.keywords = (f.keywords || []).slice(); }
      try {
        await this.api('/api/knowledge', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        this.kbModalOpen = false;
        this.kbLoaded = false;           // 允许重新拉取最新列表
        this.loadKB();
        this.toast(f.id ? '已更新知识库条目' : '已保存到知识库');
      } catch (e) { this.toast('保存失败：' + e.message); }
    },

    deleteKb(id) {
      const self = this;
      this.confirm('确定删除该知识库条目？删除后其切片也将级联清除。', async function () {
        try {
          await self.api('/api/knowledge/' + id, { method: 'DELETE' });
          self.kbLoaded = false;
          self.loadKB();
          self.toast('已删除');
        } catch (e) { self.toast('删除失败：' + e.message); }
      });
    },

    /* 详情预览（GET /{id}） */
    // fallbackKeywords：列表行里已有的完整关键词，详情接口没返回时用它兜底（列表是全量，只是展示时截断）
    async openKbDetail(id, fallbackKeywords) {
      try {
        const d = await this.api('/api/knowledge/' + id) || {};
        let kws = Array.isArray(d.keywords) ? d.keywords : [];
        if (!kws.length && Array.isArray(fallbackKeywords)) { kws = fallbackKeywords; }
        this.kbDetail = {
          id: d.id,
          name: (d.content || '').replace(/\s+/g, ' ').slice(0, 30) || '知识库条目',
          content: d.content || '',
          remark: d.remark || '',
          keywords: kws,
          createTime: this.fmtTime(d.createTime),
          updateTime: this.fmtTime(d.updateTime)
        };
        this.kbDetailOpen = true;
      } catch (e) { this.toast('详情加载失败：' + e.message); }
    },

    /* 语义检索（GET /semantic） */
    async semanticSearch() {
      const text = (this.kbSemanticText || '').trim();
      if (!text) { this.toast('请输入要检索的问题'); return; }
      this.kbLoading = true;
      try {
        const list = await this.api('/api/knowledge/semantic?text=' + encodeURIComponent(text) + '&topK=5&threshold=' + this.kbThreshold) || [];
        this.kbSemanticResults = (list || []).map(function (v) {
          return { baseId: v.baseId, content: v.content || '', keyword: v.keyword || '', score: v.score };
        });
        this.kbSemanticActive = true;
      } catch (e) {
        this.toast('语义检索失败：' + e.message);
        this.kbSemanticResults = [];
      } finally {
        this.kbLoading = false;
      }
    },

    clearSemantic() {
      this.kbSemanticActive = false;
      this.kbSemanticResults = [];
      this.kbSemanticText = '';
    },

    /* 「重置」：清空搜索框 + 退出两种搜索态，回到全量列表第一页 */
    resetKbSearch() {
      this.kbKeyword = '';
      this.kbSearchKw = '';
      this.kbPage = 1;
      this.clearSemantic();
      this.loadKB();
    },

    /* 统一列表数据源：语义检索命中时复用同一张表的列与样式，仅文字随检索结果变化 */
    kbDisplay() {
      if (this.kbSemanticActive) {
        return (this.kbSemanticResults || []).map(function (v) {
          const content = (v.content || '').replace(/\s+/g, ' ');
          return {
            id: v.baseId,
            snippet: content.slice(0, 90),
            keywords: v.keyword ? [v.keyword] : [],
            indexed: true,
            time: '',
            score: v.score,
            isSem: true
          };
        });
      }
      return this.kbDocs || [];
    },

    /* ===================== 定时任务（后端 /api/schedule） ===================== */
    async loadSched() {
      this.schedLoading = true;
      try {
        const url = '/api/schedule/page?keyword=' + encodeURIComponent(this.schedKeyword || '') +
          '&page=' + this.schedPage + '&size=' + this.schedPageSize;
        const data = await this.api(url) || {};
        const list = (data && data.list) ? data.list : [];
        const total = (data && data.total != null) ? data.total : list.length;
        this.schedTotal = total;
        this.schedPageCount = Math.max(1, Math.ceil(total / this.schedPageSize));
        if (this.schedPage > this.schedPageCount) { this.schedPage = this.schedPageCount; }
        this.schedTasks = list.map(function (v) {
          return {
            id: v.id,
            name: v.name || '未命名',
            groupName: v.groupName || 'DEFAULT',
            description: v.description || '',
            cron: v.cron || '',
            invokeTarget: v.invokeTarget || '',
            concurrent: v.concurrent === 1 ? 1 : 0,
            timeout: v.timeout != null ? v.timeout : 600,
            status: v.status === 1 ? 1 : 0,
            remark: v.remark || '',
            createTime: v.createTime,
            updateTime: v.updateTime
          };
        }.bind(this));
      } catch (e) {
        console.error('[SAI] 加载定时任务失败', e);
        this.schedTasks = [];
        this.toast('定时任务加载失败：' + e.message);
      } finally {
        this.schedLoading = false;
      }
    },

    searchSched() { this.schedPage = 1; this.loadSched(); },

    schedGoto(p) {
      if (p < 1 || p > this.schedPageCount) { return; }
      this.schedPage = p;
      this.loadSched();
    },

    /* 白名单 bean（供新建/编辑弹窗的调用目标给出候选） */
    async loadSchedBeans() {
      try { this.schedBeans = (await this.api('/api/schedule/beans')) || {}; }
      catch (e) { this.schedBeans = {}; }
    },

    /* 新建 / 编辑：编辑时先拉详情拿完整字段 */
    async openSchedModal(item) {
      if (!this.schedBeans || Object.keys(this.schedBeans).length === 0) { this.loadSchedBeans(); }
      if (item && item.id) {
        try {
          const d = await this.api('/api/schedule/' + item.id) || {};
          this.schedForm = {
            id: d.id || item.id,
            name: d.name || '',
            groupName: d.groupName || 'DEFAULT',
            description: d.description || '',
            cron: d.cron || '',
            invokeTarget: d.invokeTarget || '',
            concurrent: d.concurrent === 1 ? 1 : 0,
            timeout: d.timeout != null ? d.timeout : 600,
            status: d.status === 1 ? 1 : 0,
            remark: d.remark || ''
          };
        } catch (e) {
          this.schedForm = {
            id: item.id, name: item.name || '', groupName: item.groupName || 'DEFAULT',
            description: item.description || '', cron: item.cron || '', invokeTarget: item.invokeTarget || '',
            concurrent: item.concurrent === 1 ? 1 : 0, timeout: 600, status: item.status === 1 ? 1 : 0, remark: item.remark || ''
          };
        }
      } else {
        this.schedForm = { id: '', name: '', groupName: 'DEFAULT', description: '', cron: '', invokeTarget: '', concurrent: 0, timeout: 600, status: 1, remark: '' };
      }
      this.schedModalOpen = true;
    },

    async saveSched() {
      const f = this.schedForm;
      if (!f.name || !f.name.trim()) { this.toast('请填写任务名'); return; }
      if (!f.cron || !f.cron.trim()) { this.toast('请填写 Cron 表达式'); return; }
      if (!f.invokeTarget || !f.invokeTarget.trim()) { this.toast('请填写调用目标'); return; }
      const body = {
        name: f.name.trim(),
        groupName: f.groupName || 'DEFAULT',
        description: f.description || '',
        cron: f.cron.trim(),
        invokeTarget: f.invokeTarget.trim(),
        concurrent: parseInt(f.concurrent, 10) || 0,
        timeout: parseInt(f.timeout, 10) || 600,
        status: parseInt(f.status, 10) || 0,
        remark: f.remark || ''
      };
      if (f.id) { body.id = f.id; }
      try {
        await this.api('/api/schedule', {
          method: f.id ? 'PUT' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        this.schedModalOpen = false;
        this.schedLoaded = false;          // 允许重新拉取最新列表
        this.loadSched();
        this.toast(f.id ? '已更新定时任务' : '已创建定时任务');
      } catch (e) { this.toast('保存失败：' + e.message); }
    },

    async toggleSched(id) {
      const t = this.schedTasks.find(function (x) { return x.id === id; });
      try {
        await this.api('/api/schedule/' + id + '/toggle', { method: 'POST' });
        if (t) { t.status = (t.status === 1 ? 0 : 1); }   // 本地即时翻转，避免整表刷新
        this.toast('已切换任务状态');
      } catch (e) { this.toast('操作失败：' + e.message); }
    },

    async runSched(id) {
      try {
        await this.api('/api/schedule/' + id + '/run', { method: 'POST' });
        this.toast('已触发立即执行');
      } catch (e) { this.toast('执行失败：' + e.message); }
    },

    deleteSched(id) {
      const self = this;
      this.confirm('确定删除该定时任务？删除后调度器将停止该任务。', async function () {
        try {
          await self.api('/api/schedule/' + id, { method: 'DELETE' });
          self.schedLoaded = false;
          self.loadSched();
          self.toast('已删除');
        } catch (e) { self.toast('删除失败：' + e.message); }
      });
    },

    /* ===================== 任务调度日志（后端 /api/schedule/logs） ===================== */
    async loadSchedLogs() {
      this.schedLogLoading = true;
      try {
        const type = this.schedLogType || '';
        const url = '/api/schedule/logs?limit=200' + (type ? '&type=' + encodeURIComponent(type) : '');
        const list = (await this.api(url)) || [];
        this.schedLogs = (list || []).map(function (v) {
          return {
            id: v.id,
            taskId: v.taskId,
            taskName: v.taskName || '',
            invokeTarget: v.invokeTarget || '',
            taskType: v.taskType || 'COMMON',
            success: v.success === 1 ? 1 : 0,
            errorMsg: v.errorMsg || '',
            result: v.result || '',
            costMs: v.costMs,
            time: this.fmtDateTime(v.createTime)
          };
        }.bind(this));
      } catch (e) {
        console.error('[SAI] 加载调度日志失败', e);
        this.schedLogs = [];
        this.toast('调度日志加载失败：' + e.message);
      } finally {
        this.schedLogLoading = false;
      }
    },

    schedLogFilter(type) {
      this.schedLogType = type;
      this.schedLogLoaded = false;       // 切换类型时强制重新拉取
      this.loadSchedLogs();
    },

    /* ===================== Agent 定时任务（后端 /api/agent-schedule，AGENT 分组） ===================== */
    async loadAgentSched() {
      this.agentSchedLoading = true;
      try {
        const url = '/api/agent-schedule/page?keyword=' + encodeURIComponent(this.agentSchedKeyword || '') +
          '&page=' + this.agentSchedPage + '&size=' + this.agentSchedPageSize;
        const data = await this.api(url) || {};
        const list = (data && data.list) ? data.list : [];
        const total = (data && data.total != null) ? data.total : list.length;
        this.agentSchedTotal = total;
        this.agentSchedPageCount = Math.max(1, Math.ceil(total / this.agentSchedPageSize));
        if (this.agentSchedPage > this.agentSchedPageCount) { this.agentSchedPage = this.agentSchedPageCount; }
        this.agentSchedTasks = list.map(function (v) {
          return {
            id: v.id,
            name: v.name || '未命名',
            description: v.description || '',
            cron: v.cron || '',
            message: v.message || v.invokeTarget || '',
            status: v.status === 1 ? 1 : 0
          };
        }.bind(this));
      } catch (e) {
        console.error('[SAI] 加载 Agent 定时任务失败', e);
        this.agentSchedTasks = [];
        this.toast('Agent 定时任务加载失败：' + e.message);
      } finally {
        this.agentSchedLoading = false;
      }
    },

    searchAgentSched() { this.agentSchedPage = 1; this.loadAgentSched(); },

    agentSchedGoto(p) {
      if (p < 1 || p > this.agentSchedPageCount) { return; }
      this.agentSchedPage = p;
      this.loadAgentSched();
    },

    async openAgentSchedModal(item) {
      if (item && item.id) {
        try {
          const d = await this.api('/api/agent-schedule/' + item.id) || {};
          this.agentSchedForm = {
            id: d.id || item.id,
            name: d.name || '',
            description: d.description || '',
            cron: d.cron || '',
            message: d.message || '',
            status: d.status === 1 ? 1 : 0
          };
        } catch (e) {
          this.agentSchedForm = {
            id: item.id, name: item.name || '', description: item.description || '',
            cron: item.cron || '', message: item.message || '', status: item.status === 1 ? 1 : 0
          };
        }
      } else {
        this.agentSchedForm = { id: '', name: '', description: '', cron: '', message: '', status: 1 };
      }
      this.agentSchedTab = 'edit';
      this.agentSchedModalOpen = true;
    },

    async saveAgentSched() {
      const f = this.agentSchedForm;
      if (!f.name || !f.name.trim()) { this.toast('请填写任务名'); return; }
      if (!f.cron || !f.cron.trim()) { this.toast('请填写 Cron 表达式'); return; }
      if (!f.message || !f.message.trim()) { this.toast('请填写 Agent 对话消息'); return; }
      const body = {
        name: f.name.trim(),
        description: f.description || '',
        cron: f.cron.trim(),
        message: f.message.trim(),
        status: parseInt(f.status, 10) || 0
      };
      if (f.id) { body.id = f.id; }
      try {
        await this.api('/api/agent-schedule', {
          method: f.id ? 'PUT' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        this.agentSchedModalOpen = false;
        this.agentSchedLoaded = false;     // 允许重新拉取最新列表
        this.loadAgentSched();
        this.toast(f.id ? '已更新 Agent 任务' : '已创建 Agent 任务');
      } catch (e) { this.toast('保存失败：' + e.message); }
    },

    async toggleAgentSched(id) {
      const t = this.agentSchedTasks.find(function (x) { return x.id === id; });
      try {
        await this.api('/api/agent-schedule/' + id + '/toggle', { method: 'POST' });
        if (t) { t.status = (t.status === 1 ? 0 : 1); }
        this.toast('已切换任务状态');
      } catch (e) { this.toast('操作失败：' + e.message); }
    },

    async runAgentSched(id) {
      try {
        await this.api('/api/agent-schedule/' + id + '/run', { method: 'POST' });
        this.toast('已触发立即执行');
      } catch (e) { this.toast('执行失败：' + e.message); }
    },

    deleteAgentSched(id) {
      const self = this;
      this.confirm('确定删除该 Agent 定时任务？', async function () {
        try {
          await self.api('/api/agent-schedule/' + id, { method: 'DELETE' });
          self.agentSchedLoaded = false;
          self.loadAgentSched();
          self.toast('已删除');
        } catch (e) { self.toast('删除失败：' + e.message); }
      });
    },

    /* ===================== 邮件（后端 /api/email，收发统一归档） ===================== */
    /* 列表：后端按 create_time DESC 排序；direction 为空表示收发都返回 */
    async loadEmail() {
      this.emailLoading = true;
      try {
        const url = '/api/email/list?keyword=' + encodeURIComponent(this.emailKeyword || '') +
          (this.emailDirection === '' ? '' : '&direction=' + this.emailDirection) +
          '&page=' + this.emailPage + '&size=' + this.emailPageSize;
        const data = await this.api(url) || {};
        const list = (data && data.list) ? data.list : [];
        const total = (data && data.total != null) ? data.total : list.length;
        this.emailTotal = total;
        this.emailPageCount = Math.max(1, Math.ceil(total / this.emailPageSize));
        if (this.emailPage > this.emailPageCount) { this.emailPage = this.emailPageCount; }
        this.emailList = list.map(function (v) {
          return {
            id: v.id,
            direction: v.direction === 1 ? 1 : 0,
            fromAddr: v.fromAddr || '',
            fromName: v.fromName || '',
            toAddr: v.toAddr || '',
            subject: v.subject || '',
            content: v.content || '',
            summary: v.summary || '',
            time: this.fmtDateTime(v.createTime)
          };
        }.bind(this));
      } catch (e) {
        console.error('[SAI] 加载邮件失败', e);
        this.emailList = [];
        this.toast('邮件加载失败：' + e.message);
      } finally {
        this.emailLoading = false;
      }
    },

    /* 关键词搜索：回到第一页 */
    emailSearch() {
      this.emailPage = 1;
      this.loadEmail();
    },

    /* 方向筛选：'' 全部 / 0 接收 / 1 发送 */
    emailFilter(direction) {
      this.emailDirection = direction;
      this.emailPage = 1;
      this.loadEmail();
    },

    emailGoto(p) {
      if (p < 1 || p > this.emailPageCount) { return; }
      this.emailPage = p;
      this.loadEmail();
    },

    /* 详情（只读弹窗，GET /api/email/{id}） */
    async openEmailDetail(id) {
      try {
        const d = await this.api('/api/email/' + id) || {};
        this.emailDetail = {
          id: d.id,
          direction: d.direction === 1 ? 1 : 0,
          fromAddr: d.fromAddr || '',
          fromName: d.fromName || '',
          toAddr: d.toAddr || '',
          subject: d.subject || '',
          content: d.content || '',
          summary: d.summary || '',
          hasAttachment: !!d.hasAttachment,
          attachCount: d.attachCount || 0,
          messageId: d.messageId || '',
          receivedAt: d.receivedAt || '',
          createTime: d.createTime || ''
        };
        this.emailDetailOpen = true;
      } catch (e) {
        this.toast('加载详情失败：' + e.message);
      }
    },

    // 解码 HTML 实体（&lt; &gt; &amp; &nbsp; &#123; 等）→ 真实字符。
    // 用于修复「半转义 HTML」：部分邮件正文把 > 转义成 &gt; 但 < 仍是字面量（如 <p&gt;…），
    // 直接交给 DOMPurify 时标签名里的 &gt; 不会被解码，导致标签永不闭合、整段内容被吞成空白。
    decodeHtmlEntities(str) {
      const ta = document.createElement('textarea');
      ta.innerHTML = str;
      return ta.value;
    },

    // 邮件正文安全渲染：含 HTML 标签/实体则先解码实体、再经 DOMPurify 清洗渲染（内联 style 默认保留），否则按纯文本转义并把换行转成 <br>
    emailHtml(text) {
      if (text == null || text === '') { return ''; }
      const s = String(text);
      const looksHtml = /<[a-zA-Z][\s\S]*?>|&nbsp;|&amp;|&lt;|&gt;|&#\d+;/i.test(s);
      if (looksHtml) {
        const decoded = this.decodeHtmlEntities(s);
        if (window.DOMPurify && typeof DOMPurify.sanitize === 'function') {
          return DOMPurify.sanitize(decoded);
        }
        // 库缺失时降级为纯文本转义，避免 XSS
        const d = document.createElement('div');
        d.textContent = decoded;
        return d.innerHTML;
      }
      // 纯文本：转义后把换行转成 <br>，交给容器正常排版（不再依赖 pre-wrap，避免 HTML 邮件缩进空白）
      const d = document.createElement('div');
      d.textContent = s;
      return d.innerHTML.replace(/\n/g, '<br>');
    },

    deleteEmail(id) {
      const self = this;
      this.confirm('确定删除该邮件？', async function () {
        try {
          await self.api('/api/email/' + id, { method: 'DELETE' });
          self.emailLoaded = false;
          self.loadEmail();
          self.toast('已删除');
        } catch (e) { self.toast('删除失败：' + e.message); }
      });
    },

    /* 手动触发一次抓取（POST /api/email/fetch，与定时任务走同一逻辑） */
    async fetchEmail() {
      if (this.emailFetching) { return; }
      this.emailFetching = true;
      try {
        const data = await this.api('/api/email/fetch', { method: 'POST' }) || {};
        this.toast('抓取完成，本轮新增 ' + (data.fetched != null ? data.fetched : 0) + ' 封');
        this.emailLoaded = false;
        this.loadEmail();
      } catch (e) {
        this.toast('抓取失败：' + e.message);
      } finally {
        this.emailFetching = false;
      }
    },

    /* ===================== 待办事项（后端 /api/todo） ===================== */
    async loadTodo() {
      this.todoLoading = true;
      // 查询参数白名单：后端按强类型解析，任何脏值都会在参数绑定阶段直接返回 400。
      //   done   → 仅接受 '0' / '1'（其余一律不传，视为「不限」）
      //   keyword/type → 统一 String 化，避免 null / undefined 被拼成字面量
      //   页码   → 兜底为正整数
      const kw = String(this.todoKeyword == null ? '' : this.todoKeyword);
      const doneVal = (this.todoDone === '0' || this.todoDone === '1') ? String(this.todoDone) : '';
      // 类型同样走白名单：只有合法 code（1-4）才下发，其余一律不传（视为「不限」）
      const typeVal = this.todoTypeCode(this.todoType);
      const pageNo = Number(this.todoPage) > 0 ? Math.floor(Number(this.todoPage)) : 1;
      const pageSz = Number(this.todoPageSize) > 0 ? Math.floor(Number(this.todoPageSize)) : 20;
      const url = '/api/todo/page?keyword=' + encodeURIComponent(kw) +
        (doneVal === '' ? '' : '&done=' + doneVal) +
        (typeVal === '' ? '' : '&type=' + encodeURIComponent(typeVal)) +
        '&pageNum=' + pageNo + '&pageSize=' + pageSz;
      try {
        const data = await this.api(url) || {};
        const list = (data && data.list) ? data.list : [];
        const total = (data && data.total != null) ? data.total : list.length;
        this.todoTotal = total;
        // active 取后端返回的「全局未完成数」（不受筛选影响），否则筛选后头部数字会失真
        this.todoActive = (data && data.active != null)
          ? data.active
          : list.filter(function (d) { return d.done !== 1; }).length;
        this.todoPageCount = Math.max(1, Math.ceil(total / pageSz));
        this.todoHint = (kw || doneVal !== '' || typeVal)
          ? '待完成 ' + this.todoActive + ' 项，当前筛选 ' + total + ' 项'
          : '待完成 ' + this.todoActive + ' 项 / 共 ' + total + ' 项';
        if (this.todoPage > this.todoPageCount) { this.todoPage = this.todoPageCount; }
        this.todoList = list.map(function (v) {
          const content = v.content || '';
          // 后端 type 是数字 code（tinyint），这里归一并取中文标签供列表直接展示
          const tc = this.todoTypeCode(v.type);
          return {
            id: v.id,
            title: v.title || '',
            type: tc,
            typeLabel: this.todoTypeLabel(tc),
            content: content,
            // 列表直接渲染 Markdown：在此处一次性预渲染，避免 Alpine 每次重算都跑一遍 md()
            html: content ? this.md(content) : '',
            done: v.done === 1 ? 1 : 0,
            time: this.fmtDateTime(v.updateTime || v.createTime)
          };
        }.bind(this));
      } catch (e) {
        // 打出实际 URL，便于下次直接定位是哪个参数导致的失败
        console.error('[SAI] 加载待办失败', url, e);
        this.todoList = [];
        this.toast('待办加载失败：' + e.message);
      } finally {
        this.todoLoading = false;
      }
    },

    openTodoModal(item) {
      if (item && item.id) {
        this.todoForm = {
          id: item.id,
          title: item.title || '',
          // item.type 已是归一后的字符串 code；未指定时回落到第一个候选
          type: this.todoTypeCode(item.type) || String(this.todoTypes[0].code),
          content: item.content || '',
          doneChecked: item.done === 1
        };
      } else {
        this.todoForm = { id: '', title: '', type: String(this.todoTypes[0].code), content: '', doneChecked: false };
      }
      this.todoTab = 'edit';
      this.todoModalOpen = true;
    },

    /* 点击标题查看详情：先用行数据即时展示（无等待感），再拉一次详情接口保证内容最新 */
    async openTodoDetail(item) {
      if (!item || !item.id) { return; }
      const content = item.content || '';
      this.todoDetail = {
        id: item.id,
        title: item.title || '',
        type: item.type,
        typeLabel: item.typeLabel || this.todoTypeLabel(item.type),
        content: content,
        html: item.html || (content ? this.md(content) : ''),
        done: item.done === 1 ? 1 : 0,
        createTime: '',
        updateTime: item.time || ''
      };
      this.todoDetailOpen = true;
      try {
        const d = await this.api('/api/todo/' + item.id) || {};
        if (!d || d.id == null) { return; }
        const c = d.content || '';
        this.todoDetail = {
          id: d.id,
          title: d.title || '',
          type: d.type,
          typeLabel: this.todoTypeLabel(d.type),
          content: c,
          // 同样在数据层预渲染 Markdown，避免模板里反复调用 md()
          html: c ? this.md(c) : '',
          done: d.done === 1 ? 1 : 0,
          // 为空则留空串，模板里 x-show 直接隐藏（fmtDateTime 对空值会返回「—」，不适合这里）
          createTime: d.createTime ? this.fmtDateTime(d.createTime) : '',
          updateTime: d.updateTime ? this.fmtDateTime(d.updateTime) : ''
        };
      } catch (e) {
        // 详情接口失败不打断查看：保留行数据展示，仅记录日志
        console.error('[SAI] 待办详情加载失败', item.id, e);
      }
    },

    async saveTodo() {
      const f = this.todoForm;
      const title = (f.title || '').trim();
      if (!title) { this.toast('请填写标题'); return; }
      // 类型落库为数字 code（取值见 todoTypes）；未选 / 非法则不下发，后端按「未指定」处理
      const typeCode = this.todoTypeCode(f.type);
      const body = { title: title, content: (f.content || '').trim(), done: f.doneChecked ? 1 : 0 };
      if (typeCode) { body.type = Number(typeCode); }
      if (f.id) { body.id = f.id; }
      try {
        const res = await this.api('/api/todo', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), raw: true });
        if (!res || res.code !== 200) { this.toast('保存失败：' + ((res && res.message) || '未知错误')); return; }
        this.todoModalOpen = false;
        this.loadTodo();
        this.toast('已保存');
      } catch (e) { this.toast('保存失败：' + e.message); }
    },

    deleteTodo(id) {
      const self = this;
      this.confirm('确定删除该待办？', async function () {
        try {
          const res = await self.api('/api/todo/' + id, { method: 'DELETE', raw: true });
          if (!res || res.code !== 200) { self.toast('删除失败：' + ((res && res.message) || '未知错误')); return; }
          self.loadTodo();
          self.toast('已删除');
        } catch (e) { self.toast('删除失败：' + e.message); }
      });
    },

    async toggleTodo(id) {
      try {
        const res = await this.api('/api/todo/' + id + '/toggle', { method: 'POST', raw: true });
        if (!res || res.code !== 200) { this.toast('操作失败：' + ((res && res.message) || '未知错误')); return; }
        // 状态筛选生效时，切换后该行可能已不属于当前筛选结果 → 重新拉取，避免留下不该显示的行
        if (this.todoDone !== '') { this.loadTodo(); return; }
        // 无状态筛选：原地更新，避免整表刷新闪烁；active 是全局未完成数，按方向 ±1
        const row = this.todoList.find(function (d) { return d.id === id; });
        if (row) {
          row.done = row.done === 1 ? 0 : 1;
          this.todoActive = Math.max(0, this.todoActive + (row.done === 1 ? -1 : 1));
          this.todoHint = this.todoKeyword
            ? '待完成 ' + this.todoActive + ' 项，当前筛选 ' + this.todoTotal + ' 项'
            : '待完成 ' + this.todoActive + ' 项 / 共 ' + this.todoTotal + ' 项';
        }
      } catch (e) { this.toast('操作失败：' + e.message); }
    },

    /* 标题模糊搜索：回到第一页 */
    todoSearch() {
      this.todoPage = 1;
      this.loadTodo();
    },

    /* 完成状态筛选：'' 全部 / 0 未完成 / 1 已完成 */
    todoFilterDone(done) {
      this.todoDone = done;
      this.todoPage = 1;
      this.loadTodo();
    },

    /* 类型筛选：'' 全部，'1'~'4' 为类型 code（归一后存字符串，与下拉 option 的 DOM value 对齐） */
    todoFilterType(type) {
      this.todoType = this.todoTypeCode(type);
      this.todoPage = 1;
      this.loadTodo();
    },

    /* 类型 code 归一：合法 code → 字符串 '1'~'4'；其余（null / '' / 中文标签 / 越界）→ '' */
    todoTypeCode(v) {
      const c = Number(v);
      if (!c) { return ''; }
      const hit = this.todoTypes.find(function (t) { return t.code === c; });
      return hit ? String(hit.code) : '';
    },

    /* 类型 code → 中文标签；未指定 / 越界 → '' */
    todoTypeLabel(v) {
      const c = Number(v);
      if (!c) { return ''; }
      const hit = this.todoTypes.find(function (t) { return t.code === c; });
      return hit ? hit.label : '';
    },

    todoGoto(p) {
      if (p < 1 || p > this.todoPageCount) { return; }
      this.todoPage = p;
      this.loadTodo();
    },

    /* ===================== 通用弹窗 ===================== */
    confirm(msg, cb) {
      this.confirmTitle = '请确认';
      this.confirmText = msg;
      this.confirmCb = cb || null;
      this.confirmOpen = true;
    },
    confirmOk() {
      this.confirmOpen = false;
      const cb = this.confirmCb; this.confirmCb = null;
      if (cb) { cb(); }
    },

    /* 轻提示：占位按钮给出反馈，2.2s 后自动消失 */
    toast(msg) {
      this.toastMsg = msg;
      if (this._toastT) { clearTimeout(this._toastT); }
      this._toastT = setTimeout(() => { this.toastMsg = ''; }, 2200);
    },

    /* ===================== 认证 ===================== */
    showLogin() { this.view = 'login'; },
    showWelcome() { this.view = 'welcome'; },

    async login() {
      this.loginErr = '';
      try {
        const res = await this.api('/api/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: this.loginUser, password: this.loginPass }),
          raw: true
        });
        // 登录失败时后端返回 HTTP 200 + code(1001/2001) + message，优先展示后端提示
        const data = res && res.data;
        if (!res || res.code !== 200 || !data || !data.token) {
          this.loginErr = (res && res.message) || '登录失败：未获取到令牌';
          return;
        }
        localStorage.setItem('sai_token', data.token);
        localStorage.setItem('sai_user', JSON.stringify({
          name: data.displayName || this.loginUser,
          username: data.username || this.loginUser,
          role: '管理员'
        }));
        this.user = { name: data.displayName || this.loginUser, role: '管理员' };
        this.username = data.username || this.loginUser;
        this.authenticated = true;
        this.afterLogin();
      } catch (e) {
        // 后端返回的错误信息直接展示；不再降级为演示模式
        this.loginErr = e.message || '登录失败，请检查后端是否可用';
      }
    },

    async logout() {
      try { await this.api('/api/auth/logout', { method: 'POST' }); } catch (e) { /* 无状态，忽略 */ }
      this.forceLogout('已退出登录');
    },

    forceLogout(msg) {
      localStorage.removeItem('sai_token');
      localStorage.removeItem('sai_user');
      localStorage.removeItem('sai_view');
      // 断开 SSE 长连接，避免用失效令牌无限重连
      if (this.es) { this.es.close(); this.es = null; }
      this.notifyOpen = false;
      this.notifies = [];
      this.unread = 0;
      this.esRetry = 0;
      this.authenticated = false;
      this.view = 'welcome';
      this.current = '';
      this.conversations = [];
      this.messages = [];
      // 注意：不要清空 #content —— 其内部的 chat/kb 视图由 Thymeleaf 在页面初次加载时
      // 服务端渲染，登录态切换仅靠 x-show="authenticated" 控制显隐。若在此 innerHTML='' 会
      // 永久摧毁这些片段，导致退出再登录后主区域空白、对话框/卡片样式丢失。
      if (msg) { this.loginErr = ''; }
    },

    /* ===================== 背景：神经网络节点动画 ===================== */
    initNodes() {
      const c = document.getElementById('bg-nodes');
      if (!c) { return; }
      const ctx = c.getContext('2d');
      let w = 0, h = 0, nodes = [], raf = null;
      const D = 140;
      function resize() {
        const r = c.getBoundingClientRect();
        w = c.width = Math.max(1, Math.floor(r.width));
        h = c.height = Math.max(1, Math.floor(r.height));
        const count = Math.min(70, Math.floor(w * h / 16000));
        nodes = [];
        for (let i = 0; i < count; i++) {
          nodes.push({
            x: Math.random() * w, y: Math.random() * h,
            vx: (Math.random() - 0.5) * 0.35, vy: (Math.random() - 0.5) * 0.35,
            r: Math.random() * 1.6 + 1
          });
        }
      }
      function step() {
        if (c.offsetParent === null) { raf = requestAnimationFrame(step); return; } // 隐藏时不绘制
        ctx.clearRect(0, 0, w, h);
        for (const n of nodes) {
          n.x += n.vx; n.y += n.vy;
          if (n.x < 0 || n.x > w) { n.vx *= -1; }
          if (n.y < 0 || n.y > h) { n.vy *= -1; }
        }
        for (let i = 0; i < nodes.length; i++) {
          for (let j = i + 1; j < nodes.length; j++) {
            const a = nodes[i], b = nodes[j];
            const dx = a.x - b.x, dy = a.y - b.y;
            const d = Math.hypot(dx, dy);
            if (d < D) {
              ctx.strokeStyle = 'rgba(120,205,215,' + ((1 - d / D) * 0.28) + ')';
              ctx.lineWidth = 1;
              ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
            }
          }
        }
        for (const n of nodes) {
          ctx.fillStyle = 'rgba(120,220,225,.85)';
          ctx.beginPath(); ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2); ctx.fill();
        }
        raf = requestAnimationFrame(step);
      }
      window.addEventListener('resize', resize);
      resize();
      step();
    }
  };
}

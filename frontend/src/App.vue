<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="app-title" to="/">家計簿</RouterLink>
      <nav class="app-nav" aria-label="メインナビゲーション">
        <RouterLink to="/">家計簿入力</RouterLink>
        <RouterLink to="/categories">カテゴリ管理</RouterLink>
        <RouterLink to="/payment-methods">支払い方法管理</RouterLink>
        <RouterLink to="/transfers">振替管理</RouterLink>
      </nav>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

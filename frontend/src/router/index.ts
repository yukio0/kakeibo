import { createRouter, createWebHistory } from 'vue-router'
import { authState, loadCurrentUser } from '@/auth'
import CategoryView from '@/views/CategoryView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import PasswordChangeView from '@/views/PasswordChangeView.vue'
import PaymentMethodView from '@/views/PaymentMethodView.vue'
import TransferAccountView from '@/views/TransferAccountView.vue'
import TransactionView from '@/views/TransactionView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: TransactionView,
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: {
        public: true,
      },
    },
    {
      path: '/categories',
      name: 'categories',
      component: CategoryView,
    },
    {
      path: '/payment-methods',
      name: 'payment-methods',
      component: PaymentMethodView,
    },
    {
      path: '/password',
      name: 'password',
      component: PasswordChangeView,
    },
    {
      path: '/transfers',
      name: 'transfers',
      component: TransferAccountView,
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: NotFoundView,
    },
  ],
})

router.beforeEach(async (to) => {
  if (!authState.loaded) {
    await loadCurrentUser()
  }

  if (to.meta.public) {
    if (to.name === 'login' && authState.user) {
      return { name: 'home' }
    }

    return true
  }

  if (!authState.user) {
    return {
      name: 'login',
      query: {
        redirect: to.fullPath,
      },
    }
  }

  return true
})

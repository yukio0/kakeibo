import { createRouter, createWebHistory } from 'vue-router'
import CategoryView from '@/views/CategoryView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
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

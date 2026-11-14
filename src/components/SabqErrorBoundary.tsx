import React from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';

interface State {
  hasError: boolean;
  errorMessage: string;
}

export class SabqErrorBoundary extends React.Component<
  { children: React.ReactNode },
  State
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false, errorMessage: '' };
  }

  static getDerivedStateFromError(error: Error): State {
    return {
      hasError: true,
      errorMessage: error?.message || 'حدث خطأ غير متوقع في الواجهة.',
    };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div
          dir="rtl"
          className="flex min-h-screen flex-col items-center justify-center bg-[#06070D] p-6 text-center text-slate-100"
        >
          <div className="max-w-md rounded-3xl border border-rose-500/40 bg-[#0D101E] p-6 space-y-4">
            <AlertTriangle className="mx-auto h-10 w-10 text-rose-400" />
            <h2 className="text-lg font-bold text-white">تعذر عرض هذا الجزء مؤقتًا</h2>
            <p className="text-xs text-slate-300">
              تم التقاط الخطأ ومنع إغلاق التطبيق للحفاظ على بياناتك وجلستك:
            </p>
            <div className="rounded-xl border border-slate-800 bg-[#070912] p-2.5 text-[11px] text-rose-200 font-mono" dir="ltr">
              {this.state.errorMessage}
            </div>
            <button
              onClick={() => this.setState({ hasError: false, errorMessage: '' })}
              className="inline-flex min-h-[44px] items-center gap-2 rounded-xl bg-gradient-to-l from-violet-600 to-cyan-500 px-5 py-2 text-xs font-bold text-white"
            >
              <RefreshCw className="h-4 w-4" />
              <span>استعادة الجلسة والمتابعة</span>
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

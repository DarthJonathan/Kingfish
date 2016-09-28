package com.directdev.portal.network

import android.content.Context
import com.crashlytics.android.Crashlytics
import com.directdev.portal.BuildConfig
import com.directdev.portal.R
import com.directdev.portal.model.*
import com.directdev.portal.utils.NullConverterFactory
import com.directdev.portal.utils.readPref
import com.directdev.portal.utils.savePref
import com.facebook.stetho.okhttp3.StethoInterceptor
import io.realm.Realm
import io.realm.RealmObject
import io.realm.RealmResults
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.joda.time.DateTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.HttpException
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object DataApi {
    var isActive = false
    private val baseUrl = "https://binusmaya.binus.ac.id/services/ci/index.php/"
    private val api = buildRetrofit()

    fun initializeApp(ctx: Context, captcha: String): Single<Unit> {
        var cookie = ctx.readPref(R.string.cookie, "") as String
        return signIn(ctx, cookie, captcha).flatMap {
            val headerCookie = it.headers().get("Set-Cookie")
            if (headerCookie != null) {
                if (cookie == "") cookie = headerCookie
                cookie.savePref(ctx, R.string.cookie)
            }
            api.getTerms(cookie).subscribeOn(Schedulers.io())
        }.flatMap {
            terms ->
            Crashlytics.log("initializeApp Term Data " + terms.toString())
            val single: Single<Array<Any>>
            if (terms.size == 1) {
                single = fetchGrades(terms, cookie)[0].map {
                    arrayOf<Any>(it)
                }
            } else {
                single = Single.zip(fetchGrades(terms, cookie), {
                    grades ->
                    grades
                })
            }
            single.zipWith(api.getProfile(cookie).subscribeOn(Schedulers.io()), {
                grades, profile ->
                saveProfile(ctx, profile)
                profile.close()
                grades
            }).zipWith(fetchCourses(terms, cookie), {
                grades, courses ->
                val realm = Realm.getDefaultInstance()
                realm.executeTransaction {
                    realm ->
                    realm.insertOrUpdate(terms)
                    realm.insertOrUpdate(courses)
                    realm.delete(ScoreModel::class.java)
                    grades.forEach { realm.insertGrade(it as GradeModel) }
                }
                realm.close()
            }).zipWith(fetchRecent(ctx, cookie, terms[0].value.toString()), {
                a, b ->
            }).map {
                DateTime.now().toString().savePref(ctx, R.string.last_update)
            }
        }.doOnSubscribe {

            isActive = true
        }.doOnError {

            isActive = false
        }.doOnSuccess {

            isActive = false
        }
    }

    fun fetchData(ctx: Context, captcha: String): Single<Unit> {
        var cookie = ctx.readPref(R.string.cookie, "") as String
        val realm = Realm.getDefaultInstance()
        return signIn(ctx, cookie, captcha).flatMap {
            val term = realm.where(TermModel::class.java).max("value")
            val headerCookie = it.headers().get("Set-Cookie")
            if (headerCookie != null) {
                if (cookie == "") cookie = headerCookie
                cookie.savePref(ctx, R.string.cookie)
            }
            fetchRecent(ctx, cookie, term.toString())
        }.map { terms ->
            realm.close()
            DateTime.now().toString().savePref(ctx, R.string.last_update)
        }.doOnSubscribe {

            isActive = true
        }.doOnError {

            isActive = false
        }.doOnSuccess {

            isActive = false
        }
    }

    fun fetchResources(ctx: Context, data: RealmResults<CourseModel>, captcha: String): Single<Unit> {
        isActive = true
        var cookie = ctx.readPref(R.string.cookie, "") as String
        return signIn(ctx, cookie, captcha).flatMap {
            val headerCookie = it.headers().get("Set-Cookie")
            if (headerCookie != null) {
                cookie = headerCookie
                cookie.savePref(ctx, R.string.cookie)
            }
            Single.zip(data.map {
                val classNumber = it.classNumber
                api.getResources(
                        it.courseId,
                        it.crseId,
                        it.term.toString(),
                        it.ssrComponent,
                        it.classNumber.toString(),
                        cookie
                ).map { data ->
                    data.classNumber = classNumber
                    data
                }.subscribeOn(Schedulers.io())
            }, {
                resources ->
                val realm = Realm.getDefaultInstance()
                realm.executeTransaction { realm ->
                    resources.forEach {
                        val resModel = ResModel()
                        resModel.book.addAll((it as ResModelIntermidiary).book)
                        resModel.path.addAll(it.path)
                        resModel.resources.addAll(it.resources)
                        resModel.url.addAll(it.url)
                        resModel.webContent = it.webContent
                        resModel.classNumber = it.classNumber
                        realm.insertOrUpdate(resModel)
                    }
                }
            })
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).doOnSubscribe {
            isActive = true
        }.doOnError {
            isActive = false
        }.doOnSuccess {
            isActive = false
        }
    }

    fun fetchCaptcha(ctx: Context): Single<ResponseBody> {
        var cookie = ctx.readPref(R.string.cookie, "") as String
        return signIn(ctx, cookie).flatMap {
            val headerCookie = it.headers().get("Set-Cookie")
            if (headerCookie != null) {
                cookie = headerCookie
                cookie.savePref(ctx, R.string.cookie)
            }
            api.getCaptchaImage(cookie).subscribeOn(Schedulers.io())
        }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
    }

    fun fetchAssignment(ctx: Context, data: RealmResults<CourseModel>): Single<Unit> {
        isActive = true
        val cookie = ctx.readPref(R.string.cookie, "") as String
        return signIn(ctx, cookie).flatMap {
            Single.zip(data.map {
                val classNumber = it.classNumber
                api.getAssignment(
                        it.courseId,
                        it.crseId,
                        it.term.toString(),
                        it.ssrComponent,
                        it.classNumber.toString(),
                        cookie
                ).map { data ->
                    data.forEach { it.classNumber = classNumber }
                    data
                }.subscribeOn(Schedulers.io())
            }, {
                assignment ->
                val realm = Realm.getDefaultInstance()
                realm.executeTransaction { realm ->
                    val list = mutableListOf<AssignmentIndividualModel>()
                    (assignment.filterIsInstance<List<AssignmentIndividualModel>>()).forEach {
                        list.addAll(it)
                    }
                    realm.cleanInsert(list)
                }
            })
        }
    }

    fun decideFailedString(it: Throwable): String {
        return when (it) {
            is SocketTimeoutException -> "Request Timed Out"
            is HttpException -> {
                Crashlytics.log("HttpException")
                Crashlytics.logException(it)
                "Binusmaya's server seems to be offline, try again later"
            }
            is ConnectException -> "Failed to connect to Binusmaya"
            is SSLException -> "Failed to connect to Binusmaya"
            is UnknownHostException -> "Failed to connect to Binusmaya"
            is IOException -> "Wrong username, password, or captcha"
            is IndexOutOfBoundsException -> {
                Crashlytics.log("IndexOutOfBoundsException")
                Crashlytics.logException(it)
                "Binusmaya server is acting weird, try again later"
            }
            else -> {
                Crashlytics.log("Unknown CrashOnSignIn")
                Crashlytics.logException(it)
                "We have no idea what went wrong, but we have received the error log, we'll look into this"
            }
        }
    }


    private fun signIn(ctx: Context, cookie: String = "", captcha: String = "") = api.signIn(
            ctx.readPref(R.string.username, "") as String,
            ctx.readPref(R.string.password, "") as String,
            "",
            cookie)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())

    private fun fetchGrades(terms: List<TermModel>, cookie: String): List<Single<GradeModel>> =
            terms.map {
                api.getGrades(it.value.toString(), cookie).subscribeOn(Schedulers.io())
            }

    private fun fetchCourses(terms: List<TermModel>, cookie: String): Single<List<CourseModel>> {
        val single: Single<List<CourseModel>>
        Crashlytics.log("fetchCourses Term Data " + terms.toString())
        if (terms.size == 1) {
            single = api.getCourse(terms[0].value.toString(), cookie)
                    .subscribeOn(Schedulers.io())
                    .map {
                        it.courses.forEach { it.term = terms[0].value }
                        it.courses
                    }
        } else {
            single = Single.zip(terms.drop(1).map({ term ->
                api.getCourse(term.value.toString(), cookie)
                        .subscribeOn(Schedulers.io())
                        .map {
                            it.courses.forEach { it.term = term.value }
                            it.courses
                        }
            }), {
                val listOfCourses = mutableListOf<CourseModel>()
                val itList = it.filterIsInstance<List<CourseModel>>()
                itList.forEach { listOfCourses.addAll(it) }
                listOfCourses
            })
        }
        return single
    }

    private fun fetchRecent(ctx: Context, cookie: String, term: String) = Single.zip(
            api.getFinances(cookie).subscribeOn(Schedulers.io()),
            api.getSessions(cookie).subscribeOn(Schedulers.io()),
            api.getExams(ExamRequestBody(term), cookie).subscribeOn(Schedulers.io()),
            api.getGrades(term.toString(), cookie).subscribeOn(Schedulers.io()),
            api.getFinanceSummary(cookie).subscribeOn(Schedulers.io()),
            api.getCourse(term, cookie).subscribeOn(Schedulers.io()),
            { finance, session, exam, grade, financeSummary, course ->
                val realm = Realm.getDefaultInstance()
                realm.executeTransaction {
                    it.delete(JournalModel::class.java)
                    it.delete(ExamModel::class.java)
                    it.delete(FinanceModel::class.java)
                    it.delete(SessionModel::class.java)
                    it.insertOrUpdate(mapToJournal(exam, finance, session))
                    it.insertGrade(grade)
                    saveFinanceSummary(ctx, financeSummary)
                    saveCourse(course, term, it)
                }
                realm.close()
                isActive = false
            })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())


    /**
     * Helper Functions for saving data
     * */

    private fun saveCourse(course: CourseWrapperModel, term: String, realm: Realm) {
        course.courses.forEach {
            it.term = term.toInt()
        }
        realm.insertOrUpdate(course.courses)
    }

    private fun mapToJournal(exam: List<ExamModel>, finance: List<FinanceModel>, session: List<SessionModel>): MutableList<JournalModel> {
        val items = mutableListOf<JournalModel>()
        finance.forEach { items.add(JournalModel(it.dueDate).setDate()) }
        exam.forEach { items.add(JournalModel(it.date).setDate("yyyy-MM-dd")) }
        session.forEach { items.add(JournalModel(it.date).setDate()) }
        items.forEach { item ->
            session.forEach { if (item.id == it.date) item.session.add(it) }
            finance.forEach { if (item.id == it.dueDate) item.finance.add(it) }
            exam.forEach { if (item.id == it.date) item.exam.add(it) }
        }
        return items
    }

    private fun saveProfile(ctx: Context, response: ResponseBody) {
        try {
            val profile = JSONObject(response.string()).getJSONArray("Profile").getJSONObject(0)
            profile.getString("ACAD_PROG_DESCR").savePref(ctx, R.string.major)
            profile.getString("ACAD_CAREER_DESCR").savePref(ctx, R.string.degree)
            profile.getString("BIRTHDATE").savePref(ctx, R.string.birthday)
            profile.getString("NAMA").savePref(ctx, R.string.name)
            profile.getString("NIM").savePref(ctx, R.string.nim)
        } catch (e: JSONException) {
            Crashlytics.log(response.string())
            Crashlytics.logException(e)
            throw e
        }
    }

    private fun saveFinanceSummary(ctx: Context, response: ResponseBody) {
        try {
            val responseJson = JSONArray(response.string())
            if (responseJson.length() == 0) return
            val summary = responseJson.getJSONObject(0)
            summary.getInt("charge").savePref(ctx, R.string.finance_charge)
            summary.getInt("payment").savePref(ctx, R.string.finance_payment)
        } catch (e: JSONException) {
            Crashlytics.log(response.string())
            Crashlytics.logException(e)
        }
    }

    private fun Realm.insertGrade(data: GradeModel) {
        data.credit.term = data.term.toInt()
        cleanInsert(data.gradings)
        insert(data.scores)
        insertOrUpdate(data.credit)
    }

    private fun Realm.cleanInsert(data: List<RealmObject>) {
        if (data.size == 0) return
        delete(data[0].javaClass)
        insert(data)
    }

    private fun buildRetrofit(): DataService {
        val client: OkHttpClient
        if (BuildConfig.DEBUG) client = buildDebugClient()
        else client = buildClient()

        return Retrofit.Builder()
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .addConverterFactory(NullConverterFactory())
                .addConverterFactory(MoshiConverterFactory.create())
                .client(client)
                .baseUrl(baseUrl)
                .build().create(DataService::class.java)
    }

    private fun buildDebugClient() = OkHttpClient().newBuilder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor(StethoInterceptor())
            .followRedirects(false)
            .build()

    private fun buildClient() = OkHttpClient().newBuilder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
}
